package datadog.trace.civisibility.source.index;

import datadog.instrument.utils.ClassNameTrie;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.domain.Language;
import datadog.trace.civisibility.source.Utils;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepoIndexBuilder implements RepoIndexProvider {

  private static final Logger log = LoggerFactory.getLogger(RepoIndexBuilder.class);

  private final Config config;
  private final String repoRoot;
  private final PackageResolver packageResolver;
  private final ResourceResolver resourceResolver;
  private final FileSystem fileSystem;

  private final Object indexInitializationLock = new Object();
  private volatile RepoIndex index;

  public RepoIndexBuilder(
      Config config,
      @Nonnull String repoRoot,
      PackageResolver packageResolver,
      ResourceResolver resourceResolver,
      FileSystem fileSystem) {
    this.config = config;
    this.repoRoot = repoRoot;
    this.packageResolver = packageResolver;
    this.resourceResolver = resourceResolver;
    this.fileSystem = fileSystem;
  }

  @Override
  public RepoIndex getIndex() {
    if (index == null) {
      synchronized (indexInitializationLock) {
        if (index == null) {
          index = doGetIndex();
        }
      }
    }
    return index;
  }

  private RepoIndex doGetIndex() {
    log.debug("Building index of source files in {}", repoRoot);

    Path repoRootPath = fileSystem.getPath(repoRoot);
    RepoIndexingFileVisitor fileVisitor =
        new RepoIndexingFileVisitor(config, packageResolver, resourceResolver, repoRootPath);

    long startTime = System.currentTimeMillis();
    try {
      Files.walkFileTree(
          repoRootPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, fileVisitor);
    } catch (Exception e) {
      log.debug("Failed to build index of {}", repoRootPath, e);
    }

    long duration = System.currentTimeMillis() - startTime;
    RepoIndexingStats stats = fileVisitor.indexingStats;
    RepoIndex index = fileVisitor.getIndex();
    log.debug(
        "Indexing took {} ms. Files visited: {}, source files visited: {}, resource files visited: {}, source roots found: {}, root packages found: {}",
        duration,
        stats.filesVisited,
        stats.sourceFilesVisited,
        stats.resourceFilesVisited,
        fileVisitor.sourceRoots.size(),
        index.getRootPackages());
    return index;
  }

  private static final class RepoIndexingFileVisitor implements FileVisitor<Path> {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexingFileVisitor.class);

    private final PackageResolver packageResolver;
    private final ResourceResolver resourceResolver;
    private final ClassNameTrie.Builder trieBuilder;
    private final Map<String, String> trieKeyToPath;
    private final Collection<String> duplicateTrieKeys;
    private final Map<RepoIndex.SourceRoot, Integer> sourceRoots;
    private final PackageTree packageTree;
    private final RepoIndexingStats indexingStats;
    private final Path repoRoot;
    private final AtomicInteger sourceRootCounter;
    private final boolean followSymlinks;

    private RepoIndexingFileVisitor(
        Config config,
        PackageResolver packageResolver,
        ResourceResolver resourceResolver,
        Path repoRoot) {
      this.packageResolver = packageResolver;
      this.resourceResolver = resourceResolver;
      this.repoRoot = repoRoot;
      trieBuilder = new ClassNameTrie.Builder();
      trieKeyToPath = new HashMap<>();
      duplicateTrieKeys = new HashSet<>();
      sourceRoots = new HashMap<>();
      packageTree = new PackageTree(config);
      indexingStats = new RepoIndexingStats();
      sourceRootCounter = new AtomicInteger();
      followSymlinks = config.isCiVisibilityRepoIndexFollowSymlinks();
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      if (Files.isSymbolicLink(dir)) {
        if (!followSymlinks) {
          // Configured to skip symlinks
          return FileVisitResult.SKIP_SUBTREE;
        }
        if (readSymbolicLink(dir).startsWith(repoRoot)) {
          // The path is a symlink that points inside the repo.
          // We'll visit the folder that it points to anyway,
          // moreover, we don't want two different results for one file
          // (one containing the symlink, the other - the actual folder).
          return FileVisitResult.SKIP_SUBTREE;
        }
      }
      return FileVisitResult.CONTINUE;
    }

    private static Path readSymbolicLink(Path path) {
      try {
        return Files.readSymbolicLink(path);
      } catch (Exception e) {
        log.debug("Could not read symbolic link {}", path, e);
        return path;
      }
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      indexingStats.filesVisited++;

      try {
        String fileName = file.getFileName().toString();
        Language language = Language.getByFileName(fileName);
        if (language == null) {
          // unknown language/filetype, skip indexing
          return FileVisitResult.CONTINUE;
        }

        Path sourceRoot =
            language.isNonCode() ? getNonCodeSourceRoot(file) : getCodeSourceRoot(language, file);
        if (sourceRoot != null) {
          String relativeSourceRoot = repoRoot.relativize(sourceRoot).toString();
          int sourceRootIdx =
              sourceRoots.computeIfAbsent(
                  new RepoIndex.SourceRoot(relativeSourceRoot, language),
                  sr -> sourceRootCounter.getAndIncrement());

          String relativePath = sourceRoot.relativize(file).toString();
          if (!relativePath.isEmpty()) {
            String key = Utils.toTrieKey(relativePath);
            trieBuilder.put(key, sourceRootIdx);

            String existingPath = trieKeyToPath.put(key, file.toString());
            if (existingPath != null) {
              log.debug("Duplicate repo index key: {} - {}", existingPath, file);
              duplicateTrieKeys.add(key);
            }
          }
        }
      } catch (Exception e) {
        log.debug("Failed to index file {}", file, e);
      }
      return FileVisitResult.CONTINUE;
    }

    private Path getCodeSourceRoot(Language language, Path file) throws IOException {
      indexingStats.sourceFilesVisited++;
      Path packagePath = packageResolver.getPackage(file);
      if (packagePath != null) {
        packageTree.add(packagePath);

        Path folder = file.getParent();
        if (folder.endsWith(packagePath)) {
          // In non-JVM languages package names do not have to correspond to folder structure,
          // so using package to find source root is not always possible
          return folder
              .getRoot()
              .resolve(folder.subpath(0, folder.getNameCount() - packagePath.getNameCount()));
        }
      }

      if (language != Language.JAVA) {
        // Fallback for non-JVM languages
        return resourceResolver.getResourceRoot(file);
      } else {
        // For Java assuming default package
        return file.getParent();
      }
    }

    private Path getNonCodeSourceRoot(Path file) throws IOException {
      indexingStats.resourceFilesVisited++;
      return resourceResolver.getResourceRoot(file);
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      if (exc != null) {
        log.debug("Failed to visit file: {}", file, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      if (exc != null) {
        log.debug("Failed to visit directory: {}", dir, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    public RepoIndex getIndex() {
      RepoIndex.SourceRoot[] roots = new RepoIndex.SourceRoot[sourceRoots.size()];
      for (Map.Entry<RepoIndex.SourceRoot, Integer> e : sourceRoots.entrySet()) {
        roots[e.getValue()] = e.getKey();
      }

      return new RepoIndex(
          trieBuilder.buildTrie(), duplicateTrieKeys, Arrays.asList(roots), packageTree.asList());
    }
  }

  private static final class RepoIndexingStats {
    int filesVisited;
    int sourceFilesVisited;
    int resourceFilesVisited;
  }
}

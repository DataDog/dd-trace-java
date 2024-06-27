package datadog.trace.civisibility.source.index;

import datadog.trace.api.Config;
import datadog.trace.civisibility.source.Utils;
import datadog.trace.util.ClassNameTrie;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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
      String repoRoot,
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

    Path repoRootPath = toRealPath(fileSystem.getPath(repoRoot));
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

  private Path toRealPath(Path path) {
    try {
      return path.toRealPath();
    } catch (Exception e) {
      log.debug("Could not determine real path for {}", path, e);
      return path;
    }
  }

  private static final class RepoIndexingFileVisitor implements FileVisitor<Path> {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexingFileVisitor.class);

    private final PackageResolver packageResolver;
    private final ResourceResolver resourceResolver;
    private final ClassNameTrie.Builder trieBuilder;
    private final LinkedHashSet<RepoIndex.SourceRoot> sourceRoots;
    private final PackageTree packageTree;
    private final RepoIndexingStats indexingStats;
    private final Path repoRoot;

    private RepoIndexingFileVisitor(
        Config config,
        PackageResolver packageResolver,
        ResourceResolver resourceResolver,
        Path repoRoot) {
      this.packageResolver = packageResolver;
      this.resourceResolver = resourceResolver;
      this.repoRoot = repoRoot;
      trieBuilder = new ClassNameTrie.Builder();
      sourceRoots = new LinkedHashSet<>();
      packageTree = new PackageTree(config);
      indexingStats = new RepoIndexingStats();
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      if (Files.isSymbolicLink(dir) && readSymbolicLink(dir).startsWith(repoRoot)) {
        // The path is a symlink that points inside the repo.
        // We'll visit the folder that it points to anyway,
        // moreover, we don't want two different results for one file
        // (one containing the symlink, the other - the actual folder).
        return FileVisitResult.SKIP_SUBTREE;
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
            language.isNonCode() ? getNonCodeSourceRoot(file) : getCodeSourceRoot(file);
        if (sourceRoot != null) {
          String relativeSourceRoot = repoRoot.relativize(sourceRoot).toString();
          sourceRoots.add(new RepoIndex.SourceRoot(relativeSourceRoot, language));

          String relativePath = sourceRoot.relativize(file).toString();
          if (!relativePath.isEmpty()) {
            String key = Utils.toTrieKey(relativePath);
            trieBuilder.put(key, sourceRoots.size() - 1);
          }
        }
      } catch (Exception e) {
        log.debug("Failed to index file {}", file, e);
      }
      return FileVisitResult.CONTINUE;
    }

    private Path getCodeSourceRoot(Path file) throws IOException {
      indexingStats.sourceFilesVisited++;
      Path packagePath = packageResolver.getPackage(file);
      if (packagePath != null) {
        packageTree.add(packagePath);

        Path folder = file.getParent();
        // remove package path suffix from folder path to get source root
        return folder
            .getRoot()
            .resolve(folder.subpath(0, folder.getNameCount() - packagePath.getNameCount()));
      } else {
        // assuming default package
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
      return new RepoIndex(
          trieBuilder.buildTrie(), new ArrayList<>(sourceRoots), packageTree.asList());
    }
  }

  private static final class RepoIndexingStats {
    int filesVisited;
    int sourceFilesVisited;
    int resourceFilesVisited;
  }
}

package datadog.trace.civisibility.source.index;

import datadog.trace.api.Config;
import datadog.trace.util.ClassNameTrie;
import java.io.File;
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
  private final String scanRoot;
  private final PackageResolver packageResolver;
  private final ResourceResolver resourceResolver;
  private final FileSystem fileSystem;

  private final Object indexInitializationLock = new Object();
  private volatile RepoIndex index;

  public RepoIndexBuilder(
      Config config,
      String repoRoot,
      String scanRoot,
      PackageResolver packageResolver,
      ResourceResolver resourceResolver,
      FileSystem fileSystem) {
    this.config = config;
    this.repoRoot = repoRoot;
    this.scanRoot = scanRoot;
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
    log.warn("Building index of source files in {}, repo root is {}", scanRoot, repoRoot);

    Path repoRootPath = toRealPath(fileSystem.getPath(repoRoot));
    Path scanRootPath = toRealPath(fileSystem.getPath(scanRoot));
    RepoIndexingFileVisitor repoIndexingFileVisitor =
        new RepoIndexingFileVisitor(config, packageResolver, resourceResolver, repoRootPath);

    long startTime = System.currentTimeMillis();
    try {
      Files.walkFileTree(
          scanRootPath,
          EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          Integer.MAX_VALUE,
          repoIndexingFileVisitor);
    } catch (Exception e) {
      log.debug("Failed to build index of {}", scanRootPath, e);
    }

    long duration = System.currentTimeMillis() - startTime;
    RepoIndexingStats stats = repoIndexingFileVisitor.indexingStats;
    RepoIndex index = repoIndexingFileVisitor.getIndex();
    log.info(
        "Indexing took {} ms. Files visited: {}, source files visited: {}, resource files visited: {}, source roots found: {}, root packages found: {}",
        duration,
        stats.filesVisited,
        stats.sourceFilesVisited,
        stats.resourceFilesVisited,
        repoIndexingFileVisitor.sourceRoots.size(),
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
    private final LinkedHashSet<String> sourceRoots;
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
        Path sourceRoot = getSourceRoot(file);
        if (sourceRoot != null) {
          sourceRoots.add(repoRoot.relativize(sourceRoot).toString());

          Path relativePath = sourceRoot.relativize(file);
          String classNameWithExtension = relativePath.toString().replace(File.separatorChar, '.');
          if (!classNameWithExtension.isEmpty()) {
            trieBuilder.put(classNameWithExtension, sourceRoots.size() - 1);
          }
        }
      } catch (Exception e) {
        log.debug("Failed to index file {}", file, e);
      }
      return FileVisitResult.CONTINUE;
    }

    private Path getSourceRoot(Path file) throws IOException {
      String fileName = file.getFileName().toString();
      SourceType sourceType = SourceType.getByFileName(fileName);
      if (sourceType == null) {
        return null;

      } else if (!sourceType.isResource()) {
        indexingStats.sourceFilesVisited++;
        Path packagePath = packageResolver.getPackage(file);
        packageTree.add(packagePath);
        return getSourceRoot(file, packagePath);

      } else {
        indexingStats.resourceFilesVisited++;
        return resourceResolver.getResourceRoot(file);
      }
    }

    private Path getSourceRoot(Path file, Path packagePath) {
      Path folder = file.getParent();
      // remove package path suffix from folder path to get source root
      return folder
          .getRoot()
          .resolve(folder.subpath(0, folder.getNameCount() - packagePath.getNameCount()));
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

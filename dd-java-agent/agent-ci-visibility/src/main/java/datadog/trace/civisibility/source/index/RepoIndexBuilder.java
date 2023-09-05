package datadog.trace.civisibility.source.index;

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

  private final String repoRoot;
  private final PackageResolver packageResolver;
  private final FileSystem fileSystem;

  private final Object indexInitializationLock = new Object();
  private volatile RepoIndex index;

  public RepoIndexBuilder(String repoRoot, FileSystem fileSystem) {
    this.repoRoot = repoRoot;
    this.packageResolver = new PackageResolverImpl(fileSystem);
    this.fileSystem = fileSystem;
  }

  RepoIndexBuilder(String repoRoot, PackageResolver packageResolver, FileSystem fileSystem) {
    this.repoRoot = repoRoot;
    this.packageResolver = packageResolver;
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
    log.warn(
        "Building index of source files in {}. "
            + "This operation can be slow, "
            + "please consider using Datadog Java compiler plugin to avoid indexing",
        repoRoot);

    Path repoRootPath = fileSystem.getPath(repoRoot);
    RepoIndexingFileVisitor repoIndexingFileVisitor =
        new RepoIndexingFileVisitor(packageResolver, repoRootPath);

    long startTime = System.currentTimeMillis();
    try {
      Files.walkFileTree(
          repoRootPath,
          EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          Integer.MAX_VALUE,
          repoIndexingFileVisitor);
    } catch (Exception e) {
      log.error("Failed to build index repo of {}", repoRoot, e);
    }

    long duration = System.currentTimeMillis() - startTime;
    RepoIndexingStats stats = repoIndexingFileVisitor.indexingStats;
    RepoIndex index = repoIndexingFileVisitor.getIndex();
    log.info(
        "Indexing took {} ms. Files visited: {}, source files visited: {}, source roots found: {}, root packages found: {}",
        duration,
        stats.filesVisited,
        stats.sourceFilesVisited,
        repoIndexingFileVisitor.sourceRoots.size(),
        index.getRootPackages());
    return index;
  }

  private static final class RepoIndexingFileVisitor implements FileVisitor<Path> {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexingFileVisitor.class);

    private final PackageResolver packageResolver;
    private final ClassNameTrie.Builder trieBuilder;
    private final LinkedHashSet<String> sourceRoots;
    private final PackageTree packageTree;
    private final RepoIndexingStats indexingStats;
    private final Path repoRoot;

    private RepoIndexingFileVisitor(PackageResolver packageResolver, Path repoRoot) {
      this.packageResolver = packageResolver;
      this.repoRoot = repoRoot;
      trieBuilder = new ClassNameTrie.Builder();
      sourceRoots = new LinkedHashSet<>();
      packageTree = new PackageTree();
      indexingStats = new RepoIndexingStats();
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      indexingStats.filesVisited++;

      try {
        String fileName = file.getFileName().toString();
        SourceType sourceType = SourceType.getByFileName(fileName);
        if (sourceType != null) {
          indexingStats.sourceFilesVisited++;

          Path packagePath = packageResolver.getPackage(file);
          packageTree.add(packagePath);

          Path currentSourceRoot = getSourceRoot(file, packagePath);
          sourceRoots.add(repoRoot.relativize(currentSourceRoot).toString());

          Path relativePath = currentSourceRoot.relativize(file);
          String classNameWithExtension = relativePath.toString().replace(File.separatorChar, '.');
          if (!classNameWithExtension.isEmpty()) {
            trieBuilder.put(classNameWithExtension, sourceRoots.size() - 1);
          }
        }
      } catch (Exception e) {
        log.error("Failed to index file {}", file, e);
      }
      return FileVisitResult.CONTINUE;
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
        log.error("Failed to visit file: {}", file, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      if (exc != null) {
        log.error("Failed to visit directory: {}", dir, exc);
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
  }
}

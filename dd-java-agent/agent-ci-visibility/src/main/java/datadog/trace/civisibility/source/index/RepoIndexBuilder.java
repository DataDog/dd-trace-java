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
  private final SourceRootResolver sourceRootResolver;
  private final FileSystem fileSystem;

  private final Object indexInitializationLock = new Object();
  private volatile RepoIndex index;

  public RepoIndexBuilder(String repoRoot, FileSystem fileSystem) {
    this.repoRoot = repoRoot;
    this.sourceRootResolver = new SourceRootResolverImpl(fileSystem);
    this.fileSystem = fileSystem;
  }

  RepoIndexBuilder(String repoRoot, SourceRootResolver sourceRootResolver, FileSystem fileSystem) {
    this.repoRoot = repoRoot;
    this.sourceRootResolver = sourceRootResolver;
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
        new RepoIndexingFileVisitor(sourceRootResolver, repoRootPath);

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
    log.info(
        "Indexing took {} ms. Files visited: {}, source files visited: {}, source roots found: {}",
        duration,
        stats.filesVisited,
        stats.sourceFilesVisited,
        stats.sourceRoots);

    return repoIndexingFileVisitor.getIndex();
  }

  private static final class RepoIndexingFileVisitor implements FileVisitor<Path> {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexingFileVisitor.class);

    private final SourceRootResolver sourceRootResolver;
    private final ClassNameTrie.Builder trieBuilder;
    private final LinkedHashSet<String> sourceRoots;
    private final RepoIndexingStats indexingStats;
    private final Path repoRoot;

    private RepoIndexingFileVisitor(SourceRootResolver sourceRootResolver, Path repoRoot) {
      this.sourceRootResolver = sourceRootResolver;
      this.repoRoot = repoRoot;
      trieBuilder = new ClassNameTrie.Builder();
      sourceRoots = new LinkedHashSet<>();
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

          Path currentSourceRoot = sourceRootResolver.getSourceRoot(file);
          sourceRoots.add(repoRoot.relativize(currentSourceRoot).toString());
          indexingStats.sourceRoots++;

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
      return new RepoIndex(trieBuilder.buildTrie(), new ArrayList<>(sourceRoots));
    }
  }

  private static final class RepoIndexingStats {
    int filesVisited;
    int sourceFilesVisited;
    int sourceRoots;
  }
}

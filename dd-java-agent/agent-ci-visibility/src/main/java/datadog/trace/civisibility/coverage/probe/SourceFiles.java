package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.SourceMap;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the repository source files of the lines that instrumented classes cover, and caches them
 * in the classes. The lines of a class with a {@link SourceMap} can belong to several files: each
 * line is reported in the file and line the map gives, and dropped where the map gives none or the
 * file is not in the repository.
 */
final class SourceFiles {

  private static final Logger log = LoggerFactory.getLogger(SourceFiles.class);

  /** Cached in place of the path of a source file that is not in the repository. */
  private static final String NOT_IN_REPOSITORY = "";

  private final SourcePathResolver sourcePathResolver;

  SourceFiles(SourcePathResolver sourcePathResolver) {
    this.sourcePathResolver = sourcePathResolver;
  }

  /**
   * Adds the lines proven by the probes of a class to the lines of their source files.
   *
   * @return {@code false} if the class's own source file is not in the repository
   */
  boolean addLines(
      InstrumentedClass instrumentedClass,
      long[] probes,
      boolean[] initProbes,
      Map<String, BitSet> linesBySourcePath) {
    if (instrumentedClass.sourceMap() != null) {
      BitSet classLines = new BitSet();
      instrumentedClass.addLines(probes, classLines);
      instrumentedClass.addInitLines(initProbes, classLines);
      return addLines(instrumentedClass, classLines, linesBySourcePath);
    }
    String sourcePath = ownSourcePath(instrumentedClass);
    if (sourcePath == NOT_IN_REPOSITORY) {
      return false;
    }
    BitSet lines = linesBySourcePath.computeIfAbsent(sourcePath, path -> new BitSet());
    instrumentedClass.addLines(probes, lines);
    instrumentedClass.addInitLines(initProbes, lines);
    return true;
  }

  /**
   * Adds lines of a class, numbered as in the class, to the lines of their source files.
   *
   * @return {@code false} if the class's own source file is not in the repository
   */
  boolean addLines(
      InstrumentedClass instrumentedClass,
      BitSet classLines,
      Map<String, BitSet> linesBySourcePath) {
    SourceMap sourceMap = instrumentedClass.sourceMap();
    if (sourceMap == null) {
      String sourcePath = ownSourcePath(instrumentedClass);
      if (sourcePath == NOT_IN_REPOSITORY) {
        return false;
      }
      linesBySourcePath.computeIfAbsent(sourcePath, path -> new BitSet()).or(classLines);
      return true;
    }
    MappedFiles mappedFiles = mappedFiles(instrumentedClass, sourceMap);
    for (int line = classLines.nextSetBit(0); line >= 0; line = classLines.nextSetBit(line + 1)) {
      long sourceLine = sourceMap.map(line);
      if (sourceLine < 0) {
        continue;
      }
      String sourcePath = mappedFiles.sourcePaths[(int) (sourceLine >>> 32)];
      if (sourcePath != NOT_IN_REPOSITORY) {
        linesBySourcePath.computeIfAbsent(sourcePath, path -> new BitSet()).set((int) sourceLine);
      }
    }
    return mappedFiles.ownFileInRepository;
  }

  private String ownSourcePath(InstrumentedClass instrumentedClass) {
    Object resolved = instrumentedClass.getResolved();
    if (resolved == null) {
      resolved = resolveOwnSourcePath(instrumentedClass);
      instrumentedClass.setResolved(resolved);
    }
    return (String) resolved;
  }

  private MappedFiles mappedFiles(InstrumentedClass instrumentedClass, SourceMap sourceMap) {
    Object resolved = instrumentedClass.getResolved();
    if (resolved == null) {
      String ownFile =
          SmapParser.packageDirectory(instrumentedClass.name()) + instrumentedClass.sourceFile();
      String[] sourcePaths = new String[sourceMap.fileCount()];
      boolean ownFileInRepository = true;
      for (int i = 0; i < sourcePaths.length; i++) {
        String file = sourceMap.file(i);
        if (file.equals(ownFile)) {
          sourcePaths[i] = resolveOwnSourcePath(instrumentedClass);
          ownFileInRepository = sourcePaths[i] != NOT_IN_REPOSITORY;
        } else {
          sourcePaths[i] = resolveFile(file);
        }
      }
      resolved = new MappedFiles(sourcePaths, ownFileInRepository);
      instrumentedClass.setResolved(resolved);
    }
    return (MappedFiles) resolved;
  }

  private String resolveOwnSourcePath(InstrumentedClass instrumentedClass) {
    ClassLoader loader = instrumentedClass.loader();
    if (loader != null) {
      try {
        Class<?> clazz = Class.forName(instrumentedClass.name().replace('/', '.'), false, loader);
        Collection<String> sourcePaths = sourcePathResolver.getSourcePaths(clazz);
        if (sourcePaths.size() == 1) {
          return sourcePaths.iterator().next();
        }
      } catch (Throwable e) {
        log.debug("Could not resolve the source path of {}", instrumentedClass.name(), e);
      }
    }
    log.debug(
        "Skipping coverage reporting for {} because source path could not be determined",
        instrumentedClass.name());
    return NOT_IN_REPOSITORY;
  }

  /** Resolves a file named by a source map, such as {@code com/example/Util.kt}. */
  private String resolveFile(String file) {
    try {
      Collection<String> sourcePaths = sourcePathResolver.getResourcePaths(file);
      if (sourcePaths.size() == 1) {
        return sourcePaths.iterator().next();
      }
    } catch (Throwable e) {
      log.debug("Could not resolve the source path of {}", file, e);
    }
    return NOT_IN_REPOSITORY;
  }

  /** The repository paths of the files of a source map, by file index. */
  private static final class MappedFiles {
    final String[] sourcePaths;
    final boolean ownFileInRepository;

    MappedFiles(String[] sourcePaths, boolean ownFileInRepository) {
      this.sourcePaths = sourcePaths;
      this.ownFileInRepository = ownFileInRepository;
    }
  }
}

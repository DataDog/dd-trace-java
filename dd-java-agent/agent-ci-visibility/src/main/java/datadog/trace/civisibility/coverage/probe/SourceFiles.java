package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.SourceMap;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.net.URL;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
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
    if (NOT_IN_REPOSITORY.equals(sourcePath)) {
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
      if (NOT_IN_REPOSITORY.equals(sourcePath)) {
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
      if (!NOT_IN_REPOSITORY.equals(sourcePath)) {
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
      String ownFile = ownFile(instrumentedClass);
      String[] sourcePaths = new String[sourceMap.fileCount()];
      boolean ownFileInRepository = true;
      for (int i = 0; i < sourcePaths.length; i++) {
        String file = sourceMap.file(i);
        if (file.equals(ownFile)) {
          sourcePaths[i] = resolveOwnFile(instrumentedClass, file);
          ownFileInRepository = !NOT_IN_REPOSITORY.equals(sourcePaths[i]);
        } else {
          sourcePaths[i] = resolveFile(file);
        }
      }
      resolved = new MappedFiles(sourcePaths, ownFileInRepository);
      instrumentedClass.setResolved(resolved);
    }
    return (MappedFiles) resolved;
  }

  /**
   * The file a class's own lines are in: its source file, in the directory of its package. The
   * class name can point at another file: Kotlin copies an object that an inline function creates
   * into each caller, named after the caller, with the inline function's file as source file.
   */
  private static String ownFile(InstrumentedClass instrumentedClass) {
    return SmapParser.packageDirectory(instrumentedClass.name()) + instrumentedClass.sourceFile();
  }

  private String resolveOwnSourcePath(InstrumentedClass instrumentedClass) {
    String sourcePath = resolveOwnFile(instrumentedClass, ownFile(instrumentedClass));
    if (NOT_IN_REPOSITORY.equals(sourcePath)) {
      log.debug(
          "Skipping coverage reporting for {} because source path could not be determined",
          instrumentedClass.name());
    }
    return sourcePath;
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

  /**
   * Resolves the file of a class's own lines. Where the repository has several files of that name,
   * such as the expect declaration and the actual ones of a Kotlin multiplatform module, it picks
   * the one the class was compiled from, judged by the classpath entry the class was loaded from.
   */
  private String resolveOwnFile(InstrumentedClass instrumentedClass, String file) {
    try {
      Collection<String> sourcePaths = sourcePathResolver.getResourcePaths(file);
      if (sourcePaths.size() == 1) {
        return sourcePaths.iterator().next();
      }
      if (sourcePaths.size() > 1) {
        String classpathEntry = classpathEntry(instrumentedClass);
        String sourcePath =
            classpathEntry != null ? pickCompiledSourcePath(sourcePaths, classpathEntry) : null;
        if (sourcePath != null) {
          return sourcePath;
        }
      }
    } catch (Throwable e) {
      log.debug("Could not resolve the source path of {}", file, e);
    }
    return NOT_IN_REPOSITORY;
  }

  /**
   * The classpath entry a class was loaded from, such as {@code
   * file:/repo/lib/build/classes/java/main/} or {@code jar:file:/repo/lib/build/libs/lib.jar!/}.
   */
  @Nullable
  private static String classpathEntry(InstrumentedClass instrumentedClass) {
    ClassLoader loader = instrumentedClass.loader();
    if (loader == null) {
      return null;
    }
    String resource = instrumentedClass.name() + ".class";
    URL url = loader.getResource(resource);
    if (url == null) {
      return null;
    }
    String location = url.toString();
    return location.endsWith(resource)
        ? location.substring(0, location.length() - resource.length())
        : location;
  }

  /**
   * Picks, among the repository files that share a class's file name, the one the class was
   * compiled from. A classpath entry is the build output of one module, so the file must be in the
   * module whose build directory the entry is in. Where that module has several such files, it must
   * be in the source set of the Kotlin multiplatform target the entry was built for: {@code
   * jvmMain} for {@code build/classes/kotlin/jvm/main/} and for a jar named {@code <module>-jvm}.
   *
   * @return {@code null} if no single file fits
   */
  @Nullable
  static String pickCompiledSourcePath(Collection<String> sourcePaths, String classpathEntry) {
    // a module directory can contain another module's: the longest one that fits is the module
    List<String> inModule = new ArrayList<>();
    int moduleLength = -1;
    for (String sourcePath : sourcePaths) {
      int moduleEnd = moduleEnd(sourcePath);
      if (moduleEnd < 0 || moduleEnd < moduleLength) {
        continue;
      }
      if (!isBuildOutputOf(classpathEntry, "/" + sourcePath.substring(0, moduleEnd))) {
        continue;
      }
      if (moduleEnd > moduleLength) {
        inModule.clear();
        moduleLength = moduleEnd;
      }
      inModule.add(sourcePath);
    }
    if (inModule.isEmpty()) {
      return pickByModuleDirectory(sourcePaths, classpathEntry);
    }
    if (inModule.size() == 1) {
      return inModule.get(0);
    }
    String picked = null;
    for (String sourcePath : inModule) {
      if (isBuiltFromSourceSet(classpathEntry, sourceSet(sourcePath))) {
        if (picked != null) {
          return null;
        }
        picked = sourcePath;
      }
    }
    return picked;
  }

  /**
   * Picks, among files none of which is in a {@code src} directory, the one in the deepest module
   * directory whose build output the classpath entry is in, such as {@code guava-tests/test/...}
   * for {@code .../guava-tests/target/test-classes/}. The repository's root does not count as such
   * a directory.
   *
   * @return {@code null} if no single file fits
   */
  @Nullable
  private static String pickByModuleDirectory(
      Collection<String> sourcePaths, String classpathEntry) {
    String picked = null;
    int pickedModuleEnd = 0;
    boolean several = false;
    for (String sourcePath : sourcePaths) {
      int moduleEnd = outputModuleEnd(sourcePath, classpathEntry);
      if (moduleEnd > pickedModuleEnd) {
        picked = sourcePath;
        pickedModuleEnd = moduleEnd;
        several = false;
      } else if (moduleEnd > 0 && moduleEnd == pickedModuleEnd) {
        several = true;
      }
    }
    return several ? null : picked;
  }

  /**
   * The length of the deepest directory, other than the repository's root, that a repository path
   * starts with and whose {@code build} or {@code target} directory the classpath entry is in, or 0
   * if there is none.
   */
  private static int outputModuleEnd(String sourcePath, String classpathEntry) {
    int moduleEnd = 0;
    for (int slash = sourcePath.indexOf('/');
        slash >= 0;
        slash = sourcePath.indexOf('/', slash + 1)) {
      if (isBuildOutputOf(classpathEntry, "/" + sourcePath.substring(0, slash + 1))) {
        moduleEnd = slash + 1;
      }
    }
    return moduleEnd;
  }

  /**
   * Whether a classpath entry is in the {@code build} or {@code target} directory of a module
   * directory such as {@code /okhttp/}.
   */
  private static boolean isBuildOutputOf(String classpathEntry, String moduleDirectory) {
    return classpathEntry.contains(moduleDirectory + "build/")
        || classpathEntry.contains(moduleDirectory + "target/");
  }

  /**
   * The length of the module directory a repository path starts with, such as {@code okhttp/} for
   * {@code okhttp/src/jvmMain/kotlin/...}, or -1 if the path is not in a {@code src} directory.
   */
  private static int moduleEnd(String sourcePath) {
    if (sourcePath.startsWith("src/")) {
      return 0;
    }
    int sourceDirectory = sourcePath.indexOf("/src/");
    return sourceDirectory < 0 ? -1 : sourceDirectory + 1;
  }

  /**
   * The source set of a repository path, such as {@code jvmMain} for {@code
   * okhttp/src/jvmMain/kotlin/...}.
   */
  private static String sourceSet(String sourcePath) {
    int start = moduleEnd(sourcePath) + "src/".length();
    int end = sourcePath.indexOf('/', start);
    return end < 0 ? "" : sourcePath.substring(start, end);
  }

  /**
   * Whether a classpath entry was built from a source set of a Kotlin multiplatform target: {@code
   * jvmMain} goes into {@code build/classes/kotlin/jvm/main/} and into a jar named {@code
   * <module>-jvm}, {@code jvmTest} into {@code build/classes/kotlin/jvm/test/}.
   */
  private static boolean isBuiltFromSourceSet(String classpathEntry, String sourceSet) {
    String compilation;
    if (sourceSet.endsWith("Main")) {
      compilation = "main";
    } else if (sourceSet.endsWith("Test")) {
      compilation = "test";
    } else {
      return false;
    }
    String target = sourceSet.substring(0, sourceSet.length() - compilation.length());
    if (target.isEmpty()) {
      return false;
    }
    if (classpathEntry.contains("/" + target + "/" + compilation + "/")) {
      return true;
    }
    return compilation.equals("main")
        && (classpathEntry.contains("-" + target + "-")
            || classpathEntry.contains("-" + target + ".jar"));
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

package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SharedPart;
import datadog.trace.civisibility.coverage.probe.fixtures.CoverageFixture;
import datadog.trace.civisibility.coverage.probe.fixtures.KotlinCoverageFixture;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

/** Runs fixture code under the line probes and maps the lines it covered to repository files. */
class SourceFilesTest {

  private static final String FIXTURES = CoverageFixture.class.getPackage().getName() + ".";
  private static final String FIXTURE_DIRECTORY = FIXTURES.replace('.', '/');
  private static final String KOTLIN_FILE =
      "src/test/kotlin/" + FIXTURE_DIRECTORY + "KotlinCoverageFixture.kt";
  private static final int KOTLIN_FILE_LINES = 397;
  private static final String TRANSFORMER_FILE =
      "src/test/kotlin/" + FIXTURE_DIRECTORY + "IntTransformer.kt";

  /** Knows the fixture files only, so library files are outside the repository. */
  private static final SourcePathResolver FIXTURE_FILES =
      new SourcePathResolver() {
        @Override
        public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
          return Collections.emptyList(); // source files resolve lines by path
        }

        @Override
        public Collection<String> getResourcePaths(@Nullable String relativePath) {
          if (relativePath == null || !relativePath.startsWith(FIXTURE_DIRECTORY)) {
            return Collections.emptyList();
          }
          String sourceRoot =
              relativePath.endsWith(".java") ? "src/test/java/" : "src/test/kotlin/";
          return Collections.singletonList(sourceRoot + relativePath);
        }
      };

  @Test
  void creditsInlinedLinesToTheInlineFunction() throws Exception {
    BitSet lines = kotlinLines(39); // timed { classify(7) }

    for (int line = 234; line <= 237; line++) {
      assertTrue(lines.get(line), "line " + line + " of the inline function timed");
    }
    assertTrue(lines.get(64), "the call site");
  }

  @Test
  void dropsLinesInlinedFromOutsideTheRepository() throws Exception {
    for (int scenario : new int[] {14, 15, 30, 31}) { // map, filter and forEach of the stdlib
      Map<String, BitSet> lines = coveredLines(KotlinCoverageFixture.class, scenario);

      assertEquals(Collections.singleton(KOTLIN_FILE), lines.keySet(), "scenario " + scenario);
      BitSet fileLines = lines.get(KOTLIN_FILE);
      assertTrue(
          fileLines.length() <= KOTLIN_FILE_LINES + 1,
          "scenario " + scenario + " reported a line past the end of the file: " + fileLines);
    }
  }

  @Test
  void neverReportsLinesPastTheEndOfTheFile() throws Exception {
    for (int scenario = 0; scenario < KotlinCoverageFixture.SCENARIOS; scenario++) {
      BitSet lines = kotlinLines(scenario);
      assertTrue(
          lines.length() <= KOTLIN_FILE_LINES + 1,
          "scenario " + scenario + " reported a line past the end of the file: " + lines);
    }
  }

  @Test
  void creditsAnObjectCopiedFromAnInlineFunctionToTheFunctionsFile() throws Exception {
    // transformer { it * 2 }: the copy of the object is named after KotlinCoverageFixture
    Map<String, BitSet> lines =
        coveredLines(
            KotlinCoverageFixture.class, 44, name -> name.contains("$$inlined$transformer"));

    BitSet objectLines = new BitSet();
    objectLines.set(12); // the object is created
    objectLines.set(14, 16); // transform
    assertEquals(objectLines, lines.get(TRANSFORMER_FILE), lines.keySet().toString());
    BitSet callSiteLines = new BitSet();
    callSiteLines.set(69); // the lambda, inlined into the copy
    assertEquals(callSiteLines, lines.get(KOTLIN_FILE));
  }

  @Test
  void reportsClassesWithoutASourceMapUnchanged() throws Exception {
    Map<String, BitSet> lines = coveredLines(CoverageFixture.class, 0); // branches(1)

    BitSet fileLines = lines.get("src/test/java/" + FIXTURE_DIRECTORY + "CoverageFixture.java");
    assertNotNull(fileLines, lines.keySet().toString());
    assertTrue(
        fileLines.get(133) && fileLines.get(134) && fileLines.get(140), fileLines.toString());
  }

  @Test
  void reportsAClassWhoseFileNameIsDuplicatedInTheFileItWasCompiledFrom() throws Exception {
    String file = FIXTURE_DIRECTORY + "CoverageFixture.java";
    // the fixture is compiled into the build directory of this module
    String compiled = "dd-java-agent/agent-ci-visibility/src/test/java/" + file;
    SourcePathResolver duplicates =
        new SourcePathResolver() {
          @Override
          public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
            return Collections.emptyList();
          }

          @Override
          public Collection<String> getResourcePaths(@Nullable String relativePath) {
            return file.equals(relativePath)
                ? Arrays.asList(
                    "dd-java-agent/agent-ci-visibility-copy/src/test/java/" + file, compiled)
                : Collections.emptyList();
          }
        };

    Map<String, BitSet> lines =
        coveredLines(
            CoverageFixture.class,
            0,
            name -> name.startsWith(FIXTURE_DIRECTORY + "CoverageFixture"),
            duplicates);

    assertEquals(Collections.singleton(compiled), lines.keySet());
  }

  @Test
  void picksTheFileOfTheModuleAClassWasBuiltIn() {
    List<String> sourcePaths =
        Arrays.asList(
            "lib-a/src/main/java/com/example/Util.java",
            "lib-b/src/main/java/com/example/Util.java");

    assertEquals(
        "lib-b/src/main/java/com/example/Util.java",
        SourceFiles.pickCompiledSourcePath(
            sourcePaths, "file:/repo/lib-b/build/classes/java/main/"));
    assertEquals(
        "lib-a/src/main/java/com/example/Util.java",
        SourceFiles.pickCompiledSourcePath(
            sourcePaths, "jar:file:/repo/lib-a/target/lib-a-1.0.jar!/"));
  }

  @Test
  void picksTheFileOfTheInnermostModule() {
    List<String> sourcePaths =
        Arrays.asList(
            "src/main/java/com/example/Util.java", "samples/src/main/java/com/example/Util.java");

    assertEquals(
        "samples/src/main/java/com/example/Util.java",
        SourceFiles.pickCompiledSourcePath(
            sourcePaths, "file:/repo/samples/build/classes/java/main/"));
    assertEquals(
        "src/main/java/com/example/Util.java",
        SourceFiles.pickCompiledSourcePath(sourcePaths, "file:/repo/build/classes/java/main/"));
  }

  @Test
  void picksTheActualFileOfTheTargetAClassWasBuiltFor() {
    String file = "okhttp3/internal/platform/PlatformRegistry.kt";
    String jvm = "okhttp/src/jvmMain/kotlin/" + file;
    List<String> sourcePaths =
        Arrays.asList(
            "okhttp/src/androidMain/kotlin/" + file,
            jvm,
            "okhttp/src/commonJvmAndroid/kotlin/" + file);

    assertEquals(
        jvm,
        SourceFiles.pickCompiledSourcePath(
            sourcePaths, "file:/repo/okhttp/build/classes/kotlin/jvm/main/"));
    assertEquals(
        jvm,
        SourceFiles.pickCompiledSourcePath(
            sourcePaths, "jar:file:/repo/okhttp/build/libs/okhttp-jvm-5.3.0.jar!/"));
  }

  @Test
  void picksNoFileWhenSeveralFitOrNoneDoes() {
    List<String> sourcePaths =
        Arrays.asList(
            "lib/src/main/java/com/example/Util.java", "lib/src/extra/java/com/example/Util.java");

    assertNull(
        SourceFiles.pickCompiledSourcePath(sourcePaths, "file:/repo/lib/build/classes/java/main/"));
    assertNull(SourceFiles.pickCompiledSourcePath(sourcePaths, "file:/elsewhere/classes/"));
  }

  private static BitSet kotlinLines(int scenario) throws Exception {
    BitSet lines = coveredLines(KotlinCoverageFixture.class, scenario).get(KOTLIN_FILE);
    assertNotNull(lines, "scenario " + scenario + " covered no line of " + KOTLIN_FILE);
    return lines;
  }

  /** Runs a scenario in a fresh loader and maps the lines that its classes covered. */
  private static Map<String, BitSet> coveredLines(Class<?> workload, int scenario)
      throws Exception {
    return coveredLines(workload, scenario, name -> true);
  }

  /** Maps the lines that the classes accepted by {@code classNames} covered in a scenario. */
  private static Map<String, BitSet> coveredLines(
      Class<?> workload, int scenario, Predicate<String> classNames) throws Exception {
    return coveredLines(workload, scenario, classNames, FIXTURE_FILES);
  }

  private static Map<String, BitSet> coveredLines(
      Class<?> workload,
      int scenario,
      Predicate<String> classNames,
      SourcePathResolver sourcePathResolver)
      throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Class<?> fixture = Class.forName(workload.getName(), true, loader);
    try {
      fixture.getMethod("run", int.class).invoke(null, scenario);
    } catch (InvocationTargetException e) {
      // some scenarios throw on purpose
    }
    SourceFiles sourceFiles = new SourceFiles(sourcePathResolver);
    Map<String, BitSet> linesBySourcePath = new HashMap<>();
    ProbeRecorder.flushShared();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass == null
          || instrumentedClass.loader() != loader
          || !classNames.test(instrumentedClass.name())) {
        continue;
      }
      BitSet classLines = new BitSet();
      ProbeRecorder.addSharedLines(
          SharedPart.UNOWNED, classId, classLines); // no test window is open here
      instrumentedClass.addInitLines(ProbeRecorder.PROBES[classId], classLines);
      if (!classLines.isEmpty()) {
        assertTrue(sourceFiles.addLines(instrumentedClass, classLines, linesBySourcePath));
      }
    }
    return linesBySourcePath;
  }
}

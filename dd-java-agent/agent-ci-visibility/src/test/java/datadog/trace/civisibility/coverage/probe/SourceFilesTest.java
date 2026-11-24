package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.civisibility.coverage.probe.fixtures.CoverageFixture;
import datadog.trace.civisibility.coverage.probe.fixtures.KotlinCoverageFixture;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.lang.reflect.InvocationTargetException;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

/** Runs fixture code under the line probes and maps the lines it covered to repository files. */
class SourceFilesTest {

  private static final String FIXTURES = CoverageFixture.class.getPackage().getName() + ".";
  private static final String FIXTURE_DIRECTORY = FIXTURES.replace('.', '/');
  private static final String KOTLIN_FILE =
      "src/test/kotlin/" + FIXTURE_DIRECTORY + "KotlinCoverageFixture.kt";
  private static final int KOTLIN_FILE_LINES = 290;

  /** Knows the fixture files only, so library files are outside the repository. */
  private static final SourcePathResolver FIXTURE_FILES =
      new SourcePathResolver() {
        @Override
        public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
          if (!c.getName().startsWith(FIXTURES)) {
            return Collections.emptyList();
          }
          String topLevel = c.getName().substring(FIXTURES.length());
          int nested = topLevel.indexOf('$');
          if (nested >= 0) {
            topLevel = topLevel.substring(0, nested);
          }
          boolean kotlin = topLevel.startsWith("Kotlin");
          return Collections.singletonList(
              "src/test/"
                  + (kotlin ? "kotlin/" : "java/")
                  + FIXTURE_DIRECTORY
                  + topLevel
                  + (kotlin ? ".kt" : ".java"));
        }

        @Override
        public Collection<String> getResourcePaths(@Nullable String relativePath) {
          if (relativePath == null || !relativePath.startsWith(FIXTURE_DIRECTORY)) {
            return Collections.emptyList();
          }
          return Collections.singletonList("src/test/kotlin/" + relativePath);
        }
      };

  @Test
  void creditsInlinedLinesToTheInlineFunction() throws Exception {
    BitSet lines = kotlinLines(39); // timed { classify(7) }

    for (int line = 224; line <= 227; line++) {
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
  void reportsClassesWithoutASourceMapUnchanged() throws Exception {
    Map<String, BitSet> lines = coveredLines(CoverageFixture.class, 0); // branches(1)

    BitSet fileLines = lines.get("src/test/java/" + FIXTURE_DIRECTORY + "CoverageFixture.java");
    assertNotNull(fileLines, lines.keySet().toString());
    assertTrue(
        fileLines.get(133) && fileLines.get(134) && fileLines.get(140), fileLines.toString());
  }

  private static BitSet kotlinLines(int scenario) throws Exception {
    BitSet lines = coveredLines(KotlinCoverageFixture.class, scenario).get(KOTLIN_FILE);
    assertNotNull(lines, "scenario " + scenario + " covered no line of " + KOTLIN_FILE);
    return lines;
  }

  /** Runs a scenario in a fresh loader and maps the lines that its classes covered. */
  private static Map<String, BitSet> coveredLines(Class<?> workload, int scenario)
      throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Class<?> fixture = Class.forName(workload.getName(), true, loader);
    try {
      fixture.getMethod("run", int.class).invoke(null, scenario);
    } catch (InvocationTargetException e) {
      // some scenarios throw on purpose
    }
    SourceFiles sourceFiles = new SourceFiles(FIXTURE_FILES);
    Map<String, BitSet> linesBySourcePath = new HashMap<>();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass == null || instrumentedClass.loader() != loader) {
        continue;
      }
      BitSet classLines = new BitSet();
      instrumentedClass.addUnownedLines(classLines); // no test window is open here
      instrumentedClass.addInitLines(ProbeRecorder.PROBES[classId], classLines);
      if (!classLines.isEmpty()) {
        assertTrue(sourceFiles.addLines(instrumentedClass, classLines, linesBySourcePath));
      }
    }
    return linesBySourcePath;
  }
}

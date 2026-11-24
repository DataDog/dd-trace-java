package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.civisibility.coverage.probe.fixtures.CoverageFixture;
import datadog.trace.civisibility.coverage.probe.fixtures.KotlinCoverageFixture;
import datadog.trace.civisibility.coverage.probe.fixtures.OkioWorkload;
import java.lang.reflect.InvocationTargetException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs each scenario twice, once under the line probes and once under {@link ReferenceLines}, and
 * expects the same lines and the same outcome.
 */
class ProbeAccuracyTest {

  private static final String FIXTURES = CoverageFixture.class.getPackage().getName() + ".";

  static IntStream fixtureScenarios() {
    return IntStream.range(0, CoverageFixture.SCENARIOS);
  }

  static IntStream kotlinScenarios() {
    return IntStream.range(0, KotlinCoverageFixture.SCENARIOS);
  }

  static IntStream okioScenarios() {
    return IntStream.range(0, OkioWorkload.SCENARIOS);
  }

  @ParameterizedTest
  @MethodSource("fixtureScenarios")
  void probesReportTheReferenceLinesOfJavaCode(int scenario) throws Exception {
    assertSameLines(CoverageFixture.class, scenario, FIXTURES);
  }

  @ParameterizedTest
  @MethodSource("kotlinScenarios")
  void probesReportTheReferenceLinesOfKotlinCode(int scenario) throws Exception {
    assertSameLines(KotlinCoverageFixture.class, scenario, FIXTURES);
  }

  /** Library code that was compiled by its own build rather than by this one. */
  @ParameterizedTest
  @MethodSource("okioScenarios")
  void probesReportTheReferenceLinesOfLibraryCode(int scenario) throws Exception {
    Map<String, BitSet> lines = assertSameLines(OkioWorkload.class, scenario, FIXTURES, "okio.");
    assertTrue(
        lines.keySet().stream().anyMatch(path -> path.startsWith("okio/")),
        "no okio line was covered: " + lines.keySet());
  }

  private static Map<String, BitSet> assertSameLines(
      Class<?> workload, int scenario, String... instrumented) throws Exception {
    ReferenceLines.drain();
    FixtureLoader referenceLoader = new FixtureLoader(ReferenceLines::instrument, instrumented);
    String referenceOutcome = run(referenceLoader, workload, scenario);
    Map<String, BitSet> referenceLines = ReferenceLines.drain();

    FixtureLoader probeLoader = new FixtureLoader(null, instrumented);
    String probeOutcome = run(probeLoader, workload, scenario);
    Map<String, BitSet> probeLines = probeLines(probeLoader);

    assertFalse(referenceLines.isEmpty(), "the reference recorded no lines");
    assertEquals(referenceOutcome, probeOutcome);
    assertEquals(referenceLines, probeLines);
    return referenceLines;
  }

  private static String run(ClassLoader loader, Class<?> workload, int scenario) throws Exception {
    Class<?> fixture = Class.forName(workload.getName(), true, loader);
    try {
      return String.valueOf(fixture.getMethod("run", int.class).invoke(null, scenario));
    } catch (InvocationTargetException e) {
      return "threw " + e.getCause();
    }
  }

  /** Lines of the classes this loader defined: hits outside any window plus initializers. */
  private static Map<String, BitSet> probeLines(ClassLoader loader) {
    Map<String, BitSet> lines = new HashMap<>();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass == null || instrumentedClass.loader() != loader) {
        continue;
      }
      BitSet classLines = new BitSet();
      ProbeRecorder.addSharedLines(classId, classLines);
      instrumentedClass.addInitLines(ProbeRecorder.PROBES[classId], classLines);
      if (!classLines.isEmpty()) {
        String path =
            ReferenceLines.sourcePath(instrumentedClass.name(), instrumentedClass.sourceFile());
        lines.computeIfAbsent(path, file -> new BitSet()).or(classLines);
      }
    }
    return lines;
  }
}

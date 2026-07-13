package datadog.trace.civisibility.coverage.line;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.civisibility.source.SourcePathResolver;
import org.junit.jupiter.api.Test;

class LineCoverageStoreTest {

  private static final class CoveredClass {}

  @Test
  void reportFoldsPerTestCoverageBackIntoJacocoSharedArray() {
    CiVisibilityMetricCollector metrics = mock(CiVisibilityMetricCollector.class);
    SourcePathResolver sourcePathResolver = mock(SourcePathResolver.class);
    // source path resolution is irrelevant here: the aggregate back-fill happens regardless
    when(sourcePathResolver.getSourcePaths(any())).thenReturn(emptyList());

    CoverageStore store = new LineCoverageStore.Factory(metrics, sourcePathResolver).create(null);
    CoverageProbes probes = store.getProbes();

    boolean[] jacocoProbes = new boolean[5];
    boolean[] perTest = probes.resolveProbeArray(CoveredClass.class, 42L, jacocoProbes);
    // simulate Jacoco's native probes recording coverage into the per-test array
    perTest[3] = true;

    // before report, Jacoco's shared (aggregate) array is untouched
    assertFalse(jacocoProbes[3]);

    store.report(DDTraceId.ONE, 1L, 1L);

    // after report the per-test coverage is folded back so Jacoco's aggregate stays accurate
    assertTrue(jacocoProbes[3]);
  }

  @Test
  void reportSkipsClassesWhoseProbeArrayWasResolvedButNeverWritten() {
    CiVisibilityMetricCollector metrics = mock(CiVisibilityMetricCollector.class);
    SourcePathResolver sourcePathResolver = mock(SourcePathResolver.class);
    // resolve the source path so the class would be reported if it were not skipped
    when(sourcePathResolver.getSourcePaths(any()))
        .thenReturn(singletonList("src/test/java/datadog/smoke/CoveredClass.java"));

    CoverageStore store = new LineCoverageStore.Factory(metrics, sourcePathResolver).create(null);
    CoverageProbes probes = store.getProbes();
    // a method was entered (probe array resolved) but it threw before any probe fired
    probes.resolveProbeArray(CoveredClass.class, 42L, new boolean[5]);

    boolean coverageGathered = store.report(DDTraceId.ONE, 1L, 1L);

    assertFalse(
        coverageGathered, "a test that covered no probes must not produce a coverage report");
    TestReport report = store.getReport();
    if (report != null) {
      assertTrue(report.getTestReportFileEntries().isEmpty());
    }
  }

  @Test
  void analysisCacheKeyDistinguishesClassesAndProbeSets() {
    boolean[] probes = {true, false, true};
    boolean[] sameProbes = {true, false, true};
    boolean[] otherProbes = {true, true, true};

    LineCoverageStore.AnalysisCacheKey key = new LineCoverageStore.AnalysisCacheKey(1L, probes);
    // identical class id + probe contents must collide so the analysis is reused
    assertEquals(key, new LineCoverageStore.AnalysisCacheKey(1L, sameProbes));
    assertEquals(key.hashCode(), new LineCoverageStore.AnalysisCacheKey(1L, sameProbes).hashCode());
    // a different class or a different probe set must NOT hit the same cache entry
    assertNotEquals(key, new LineCoverageStore.AnalysisCacheKey(2L, probes));
    assertNotEquals(key, new LineCoverageStore.AnalysisCacheKey(1L, otherProbes));
  }
}

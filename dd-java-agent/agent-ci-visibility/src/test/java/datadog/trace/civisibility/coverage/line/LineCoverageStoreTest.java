package datadog.trace.civisibility.coverage.line;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import datadog.trace.civisibility.coverage.line.LineCoverageStore.AnalysisCacheKey;
import datadog.trace.civisibility.source.BestEffortSourcePathResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import org.junit.jupiter.api.Test;

class LineCoverageStoreTest {

  @Test
  void unresolvedResourceDoesNotAbortCoverageReport() {
    CiVisibilityMetricCollector metrics = mock(CiVisibilityMetricCollector.class);
    SourcePathResolver delegate = mock(SourcePathResolver.class);
    when(delegate.getResourcePaths("known.feature")).thenReturn(singletonList("known.feature"));
    when(delegate.getResourcePaths("/repo/missing.feature")).thenReturn(emptyList());
    CoverageStore store =
        new LineCoverageStore.Factory(metrics, new BestEffortSourcePathResolver(delegate))
            .create(null);
    store.getProbes().recordNonCodeResource("known.feature");
    store.getProbes().recordNonCodeResource("/repo/missing.feature");

    assertTrue(store.report(DDTraceId.ONE, 1L, 1L));
    assertEquals(1, store.getReport().getTestReportFileEntries().size());
    assertEquals(
        "known.feature",
        store.getReport().getTestReportFileEntries().iterator().next().getSourceFileName());
    verify(metrics).add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
  }

  @Test
  void cacheKeyReusesAnalysisForSameClassAndProbes() {
    AnalysisCacheKey key = new AnalysisCacheKey(1L, new boolean[] {true, false, true, false});
    AnalysisCacheKey same = new AnalysisCacheKey(1L, new boolean[] {true, false, true, false});
    // identical class id + probe set must collide so the analysis is reused
    assertEquals(key, same);
    assertEquals(key.hashCode(), same.hashCode());
  }

  @Test
  void cacheKeyDistinguishesClassesAndProbeSets() {
    AnalysisCacheKey key = new AnalysisCacheKey(1L, new boolean[] {true, false, true});
    // a different class or a different probe set must NOT hit the same cache entry
    assertNotEquals(key, new AnalysisCacheKey(2L, new boolean[] {true, false, true}));
    assertNotEquals(key, new AnalysisCacheKey(1L, new boolean[] {true, true, true}));
  }

  @Test
  void cacheKeyIgnoresTrailingUnsetProbes() {
    // The key bit-packs the activated probe set; trailing probes that never fire don't change
    // coverage, so padding differences must not create distinct entries.
    AnalysisCacheKey shortKey = new AnalysisCacheKey(1L, new boolean[] {true, false, true});
    AnalysisCacheKey padded =
        new AnalysisCacheKey(1L, new boolean[] {true, false, true, false, false});
    assertEquals(shortKey, padded);
    assertEquals(shortKey.hashCode(), padded.hashCode());
  }
}

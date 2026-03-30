package datadog.trace.civisibility.coverage.file;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.SourceResolutionException;
import org.junit.jupiter.api.Test;

class FileCoverageStoreTest {

  private static final class ResolvableClassA {}

  private static final class DuplicateKeyClass {}

  private static final class ResolvableClassC {}

  @Test
  void duplicateKeyClassReturnsAllCandidatePathsInCoverageReport()
      throws SourceResolutionException {
    CiVisibilityMetricCollector metrics = mock(CiVisibilityMetricCollector.class);
    SourcePathResolver sourcePathResolver = mock(SourcePathResolver.class);
    when(sourcePathResolver.getSourcePaths(ResolvableClassA.class))
        .thenReturn(singletonList("src/main/java/com/example/ClassA.java"));
    when(sourcePathResolver.getSourcePaths(DuplicateKeyClass.class))
        .thenReturn(
            asList(
                "src/debug/java/com/example/DuplicateKeyClass.java",
                "src/release/java/com/example/DuplicateKeyClass.java"));
    when(sourcePathResolver.getSourcePaths(ResolvableClassC.class))
        .thenReturn(singletonList("src/main/java/com/example/ClassC.java"));

    CoverageStore store = new FileCoverageStore.Factory(metrics, sourcePathResolver).create(null);
    store.getProbes().record(ResolvableClassA.class);
    store.getProbes().record(DuplicateKeyClass.class);
    store.getProbes().record(ResolvableClassC.class);

    boolean result = store.report(DDTraceId.ONE, 1L, 1L);

    assertTrue(result);
    TestReport report = store.getReport();
    assertNotNull(report);
    assertEquals(4, report.getTestReportFileEntries().size());
  }

  @Test
  void coverageReportSucceedsForNonDuplicateClasses() throws SourceResolutionException {
    CiVisibilityMetricCollector metrics = mock(CiVisibilityMetricCollector.class);
    SourcePathResolver sourcePathResolver = mock(SourcePathResolver.class);
    when(sourcePathResolver.getSourcePaths(ResolvableClassA.class))
        .thenReturn(singletonList("src/main/java/com/example/ClassA.java"));
    when(sourcePathResolver.getSourcePaths(ResolvableClassC.class))
        .thenReturn(singletonList("src/main/java/com/example/ClassC.java"));

    CoverageStore store = new FileCoverageStore.Factory(metrics, sourcePathResolver).create(null);
    store.getProbes().record(ResolvableClassA.class);
    store.getProbes().record(ResolvableClassC.class);

    boolean result = store.report(DDTraceId.ONE, 1L, 1L);

    assertTrue(result);
    TestReport report = store.getReport();
    assertNotNull(report);
    assertEquals(2, report.getTestReportFileEntries().size());
  }

  @Test
  void emptySourcePathsForOneClassDoesNotKillCoverageReport() throws SourceResolutionException {
    CiVisibilityMetricCollector metrics = mock(CiVisibilityMetricCollector.class);
    SourcePathResolver sourcePathResolver = mock(SourcePathResolver.class);
    when(sourcePathResolver.getSourcePaths(ResolvableClassA.class))
        .thenReturn(singletonList("src/main/java/com/example/ClassA.java"));
    when(sourcePathResolver.getSourcePaths(DuplicateKeyClass.class)).thenReturn(emptyList());
    when(sourcePathResolver.getSourcePaths(ResolvableClassC.class))
        .thenReturn(singletonList("src/main/java/com/example/ClassC.java"));

    CoverageStore store = new FileCoverageStore.Factory(metrics, sourcePathResolver).create(null);
    store.getProbes().record(ResolvableClassA.class);
    store.getProbes().record(DuplicateKeyClass.class);
    store.getProbes().record(ResolvableClassC.class);

    boolean result = store.report(DDTraceId.ONE, 1L, 1L);

    assertTrue(result);
    TestReport report = store.getReport();
    assertNotNull(report);
    assertEquals(2, report.getTestReportFileEntries().size());
  }
}

package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.Utils;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestProbes implements CoverageProbeStore {

  private static final Logger log = LoggerFactory.getLogger(TestProbes.class);

  private static final Map<String, Integer> totalProbeCounts = new HashMap<>();

  // Unbounded data structure that only exists within a single test span
  private final Map<Class<?>, ExecutionDataAdapter> probeActivations;
  private final SourcePathResolver sourcePathResolver;
  private volatile TestReport testReport;

  TestProbes(SourcePathResolver sourcePathResolver) {
    this.sourcePathResolver = sourcePathResolver;
    probeActivations = new ConcurrentHashMap<>();
  }

  @Override
  public void record(Class<?> clazz, long classId, String className, int probeId) {
    probeActivations
        .computeIfAbsent(clazz, (ignored) -> new ExecutionDataAdapter(classId, className))
        .record(probeId);
  }

  @Override
  public void report(Long testSessionId, Long testSuiteId, long spanId) {
    Map<String, TestReportFileEntry> testReportFileEntries = new HashMap<>();
    for (Map.Entry<Class<?>, ExecutionDataAdapter> e : probeActivations.entrySet()) {
      ExecutionDataAdapter executionDataAdapter = e.getValue();
      String className = executionDataAdapter.getClassName();
      Integer totalProbeCount = totalProbeCounts.get(className);

      if (totalProbeCount == null) {
        log.debug(
            "Skipping coverage reporting for {} because total probe count is absent", className);
        continue;
      }

      Class<?> clazz = e.getKey();
      String sourcePath = sourcePathResolver.getSourcePath(clazz);
      if (sourcePath == null) {
        log.debug(
            "Skipping coverage reporting for {} because source path could not be determined",
            className);
        continue;
      }

      try (InputStream is = Utils.getClassStream(clazz)) {
        TestReportFileEntry fileEntry =
            testReportFileEntries.computeIfAbsent(sourcePath, TestReportFileEntry::new);

        ExecutionDataStore store = new ExecutionDataStore();
        store.put(executionDataAdapter.toExecutionData(totalProbeCount));

        Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(fileEntry));
        analyzer.analyzeClass(is, null);

      } catch (Exception exception) {
        log.debug(
            "Skipping coverage reporting for {} ({}) because of error",
            className,
            sourcePath,
            exception);
      }
    }

    testReport = new TestReport(testSessionId, testSuiteId, spanId, testReportFileEntries);
  }

  @Nullable
  @Override
  public TestReport getReport() {
    return testReport;
  }

  public static class TestProbesFactory implements CoverageProbeStore.Factory {

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      totalProbeCounts.put(className, totalProbeCount);
    }

    @Override
    public CoverageProbeStore create(SourcePathResolver sourcePathResolver) {
      return new TestProbes(sourcePathResolver);
    }
  }
}

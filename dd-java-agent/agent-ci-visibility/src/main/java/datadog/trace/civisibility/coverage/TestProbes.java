package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.Utils;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jacoco.core.data.ExecutionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestProbes implements CoverageProbeStore {

  private static final Logger log = LoggerFactory.getLogger(TestProbes.class);

  private static final Map<String, Integer> totalProbeCounts = new HashMap<>();

  // Unbounded data structure that only exists within a single test span
  private final Map<Class<?>, ExecutionDataAdapter> probeActivations;
  private final SourcePathResolver sourcePathResolver;

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
  public void report(Long testSessionId, long testModuleId, long testSuiteId, long spanId) {

    TestReport testReport = new TestReport(testSessionId, testModuleId, testSuiteId, spanId);

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
        ExecutionData executionData = executionDataAdapter.toExecutionData(totalProbeCount);
        testReport.generate(is, sourcePath, executionData);

      } catch (Exception exception) {
        log.debug(
            "Skipping coverage reporting for {} ({}) because of error",
            className,
            sourcePath,
            exception);
      }
    }

    testReport.log();
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

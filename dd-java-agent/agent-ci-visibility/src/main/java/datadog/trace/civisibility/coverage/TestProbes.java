package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jacoco.core.data.ExecutionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestProbes implements CoverageProbeStore {
  private static final Logger log = LoggerFactory.getLogger(TestProbes.class);

  private static final Map<String, Integer> totalProbeCounts = new HashMap<>();

  // Unbounded data structure that only exists within a single test span
  private final Map<String, ExecutionDataAdapter> probeActivations;

  TestProbes() {
    probeActivations = new HashMap<>();
  }

  @Override
  public void record(long classId, String className, int probeId) {
    probeActivations
        .computeIfAbsent(className, (ignored) -> new ExecutionDataAdapter(classId, className))
        .record(probeId);
  }

  @Override
  public void report(Long testSessionId, long testSuiteId, long spanId) {
    // Create a copy to avoid any probes during processing that might modify the probeActivations
    // map
    List<ExecutionDataAdapter> executionDataAdapterList =
        new ArrayList<>(probeActivations.values());
    List<ExecutionData> executionDataList =
        executionDataAdapterList.stream()
            .map(a -> a.toExecutionData(totalProbeCounts.get(a.getClassName())))
            .collect(Collectors.toList());

    //    executionDataList.forEach((executionData) -> {
    //      log.debug("{},{},{} -> {} -> {}", testSessionId, testSuiteId, spanId,
    // executionData.toString(), executionData.getProbes());
    //    });

    TestReport testReport = new TestReport(testSessionId, testSuiteId, spanId, executionDataList);
    testReport.generate();
  }

  public static class TestProbesFactory implements CoverageProbeStore.Factory {

    public void setTotalProbeCount(String className, int totalProbeCount) {
      totalProbeCounts.put(className, totalProbeCount);
    }

    @Override
    public CoverageProbeStore create() {
      return new TestProbes();
    }
  }
}

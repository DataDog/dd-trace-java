package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jacoco.core.data.ExecutionData;

public class TestProbes implements CoverageProbeStore {

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
  public void report(Long testSessionId, long testModuleId, long testSuiteId, long spanId) {
    // Create a copy to avoid any probes during processing that might modify the probeActivations
    // map
    List<ExecutionDataAdapter> executionDataAdapterList =
        new ArrayList<>(probeActivations.values());
    List<ExecutionData> executionDataList =
        executionDataAdapterList.stream()
            .map(
                a -> {
                  Integer totalProbeCount = totalProbeCounts.get(a.getClassName());
                  return totalProbeCount != null ? a.toExecutionData(totalProbeCount) : null;
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    TestReport testReport =
        new TestReport(testSessionId, testModuleId, testSuiteId, spanId, executionDataList);
    testReport.generate();
  }

  public static class TestProbesFactory implements CoverageProbeStore.Factory {

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      totalProbeCounts.put(className, totalProbeCount);
    }

    @Override
    public CoverageProbeStore create() {
      return new TestProbes();
    }
  }
}

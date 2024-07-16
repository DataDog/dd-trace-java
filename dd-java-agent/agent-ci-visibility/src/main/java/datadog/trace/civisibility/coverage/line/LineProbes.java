package datadog.trace.civisibility.coverage.line;

import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Coverage probes with line-level granularity, maintaining data about specific lines that are
 * covered in a file.
 */
@NotThreadSafe
public class LineProbes implements CoverageProbes {

  private final CiVisibilityMetricCollector metrics;
  private final Map<String, Integer> probeCounts;

  private final Map<Class<?>, ExecutionDataAdapter> executionData;
  private final Map<String, String> nonCodeResources;

  private Class<?> lastCoveredClass;
  private ExecutionDataAdapter lastCoveredExecutionData;

  LineProbes(CiVisibilityMetricCollector metrics, Map<String, Integer> probeCounts) {
    this.metrics = metrics;
    this.probeCounts = probeCounts;
    executionData = new IdentityHashMap<>();
    nonCodeResources = new HashMap<>();
  }

  @Override
  public void record(Class<?> clazz) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void record(Class<?> clazz, long classId, int probeId) {
    try {
      if (lastCoveredClass != clazz) {
        // optimization to avoid map lookup if activating several probes for same class in a row
        lastCoveredExecutionData =
            executionData.computeIfAbsent(
                lastCoveredClass = clazz,
                k -> new ExecutionDataAdapter(classId, k.getName(), probeCounts.get(k.getName())));
      }
      lastCoveredExecutionData.record(probeId);

    } catch (Exception e) {
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.RECORD);
      throw e;
    }
  }

  @Override
  public void recordNonCodeResource(String absolutePath) {
    nonCodeResources.put(absolutePath, null);
  }

  public Map<Class<?>, ExecutionDataAdapter> getExecutionData() {
    return executionData;
  }

  public Collection<String> getNonCodeResources() {
    return nonCodeResources.keySet();
  }
}

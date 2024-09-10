package datadog.trace.civisibility.coverage.file;

import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Coverage probes with file-level granularity, NO data about specific lines that are covered in a
 * file is gathered. The advantage of lower granularity is lower performance overhead.
 */
@NotThreadSafe
public class FileProbes implements CoverageProbes {

  private final CiVisibilityMetricCollector metrics;

  private final Map<Class<?>, Class<?>> coveredClasses;
  private final Map<String, String> nonCodeResources;

  private Class<?> lastCoveredClass;

  FileProbes(CiVisibilityMetricCollector metrics, boolean isTestThread) {
    this.metrics = metrics;
    coveredClasses = isTestThread ? new IdentityHashMap<>() : new ConcurrentHashMap<>();
    nonCodeResources = isTestThread ? new HashMap<>() : new ConcurrentHashMap<>();
  }

  @Override
  public void record(Class<?> clazz, long classId, int probeId) {
    record(clazz);
  }

  @Override
  public void record(Class<?> clazz) {
    try {
      if (lastCoveredClass != clazz) {
        // optimization to avoid map lookup when reporting same class several times in a row
        coveredClasses.put(lastCoveredClass = clazz, clazz);
      }

    } catch (Exception e) {
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.RECORD);
      throw e;
    }
  }

  @Override
  public void recordNonCodeResource(String absolutePath) {
    nonCodeResources.put(absolutePath, absolutePath);
  }

  public Collection<Class<?>> getCoveredClasses() {
    return coveredClasses.keySet();
  }

  public Collection<String> getNonCodeResources() {
    return nonCodeResources.keySet();
  }
}

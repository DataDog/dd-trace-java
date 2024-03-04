package datadog.trace.api.civisibility.telemetry;

import java.util.Collection;
import java.util.Collections;

public class NoOpMetricCollector implements CiVisibilityMetricCollector {

  public static final CiVisibilityMetricCollector INSTANCE = new NoOpMetricCollector();

  private NoOpMetricCollector() {}

  @Override
  public void add(CiVisibilityDistributionMetric metric, int value, TagValue... tags) {
    // do nothing
  }

  @Override
  public void add(CiVisibilityCountMetric metric, long value, TagValue... tags) {
    // do nothing
  }

  @Override
  public void prepareMetrics() {
    // do nothing
  }

  @Override
  public Collection<CiVisibilityMetricData> drain() {
    return Collections.emptySet();
  }
}

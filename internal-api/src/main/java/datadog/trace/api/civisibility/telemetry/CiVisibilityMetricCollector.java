package datadog.trace.api.civisibility.telemetry;

import datadog.trace.api.telemetry.MetricCollector;

public interface CiVisibilityMetricCollector extends MetricCollector<CiVisibilityMetricData> {

  void add(CiVisibilityDistributionMetric metric, int value, TagValue... tags);

  void add(CiVisibilityCountMetric metric, long value, TagValue... tags);
}

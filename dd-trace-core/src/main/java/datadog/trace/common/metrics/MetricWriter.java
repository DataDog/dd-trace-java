package datadog.trace.common.metrics;

public interface MetricWriter {
  void startBucket(int metricCount, long start, long duration);

  void add(MetricKey key, AggregateMetric aggregate);

  void finishBucket();
}

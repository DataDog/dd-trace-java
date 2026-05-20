package datadog.trace.common.metrics;

public interface MetricWriter {
  void startBucket(int metricCount, long start, long duration);

  /**
   * Serialize one aggregate. The {@link AggregateEntry} carries both the label fields (resource,
   * service, span.kind, peer tags, etc.) and the counters being reported.
   */
  void add(AggregateEntry entry);

  void finishBucket();

  void reset();
}

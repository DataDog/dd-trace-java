package datadog.metrics.api.statsd;

/** A counter identity: the dogstatsd metric name and tags a batch of counts should report under. */
public interface StatsDCounterKey {
  String getMetricName();

  String[] getTags();
}

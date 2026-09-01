package datadog.metrics.api.statsd;

/**
 * Lets an {@code enum} used as an {@code Accumulator} key declare its own dogstatsd metric name at
 * the declaration site, so the schema and its reporting name can't drift apart.
 *
 * <pre>{@code
 * enum MyCounters implements StatsDCounterKey {
 *   FOO("my.counters.foo"),
 *   BAR("my.counters.bar");
 *
 *   private final String metricName;
 *   MyCounters(String metricName) { this.metricName = metricName; }
 *   @Override public String getMetricName() { return metricName; }
 * }
 * }</pre>
 *
 * @see StatsDCountReporter
 */
public interface StatsDCounterKey {
  String getMetricName();
}

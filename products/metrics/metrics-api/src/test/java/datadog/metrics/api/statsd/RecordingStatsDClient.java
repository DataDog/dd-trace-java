package datadog.metrics.api.statsd;

import java.util.ArrayList;
import java.util.List;

/** Test fake that records every {@link #count} call for assertion. */
final class RecordingStatsDClient implements StatsDClient {

  static final class Count {
    final String metricName;
    final long delta;
    final String[] tags;

    Count(String metricName, long delta, String[] tags) {
      this.metricName = metricName;
      this.delta = delta;
      this.tags = tags;
    }
  }

  final List<Count> counts = new ArrayList<>();

  @Override
  public void incrementCounter(String metricName, String... tags) {}

  @Override
  public void count(String metricName, long delta, String... tags) {
    counts.add(new Count(metricName, delta, tags));
  }

  @Override
  public void gauge(String metricName, long value, String... tags) {}

  @Override
  public void gauge(String metricName, double value, String... tags) {}

  @Override
  public void histogram(String metricName, long value, String... tags) {}

  @Override
  public void histogram(String metricName, double value, String... tags) {}

  @Override
  public void distribution(String metricName, long value, String... tags) {}

  @Override
  public void distribution(String metricName, double value, String... tags) {}

  @Override
  public void serviceCheck(
      String serviceCheckName, String status, String message, String... tags) {}

  @Override
  public void error(Exception error) {}

  @Override
  public int getErrorCount() {
    return 0;
  }

  @Override
  public void close() {}
}

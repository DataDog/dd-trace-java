package datadog.metrics.api.statsd;

final class NoOpStatsDClient implements StatsDClient {

  @Override
  public void incrementCounter(final String metricName, final String... tags) {}

  @Override
  public void count(final String metricName, final long delta, final String... tags) {}

  @Override
  public void gauge(final String metricName, final long value, final String... tags) {}

  @Override
  public void gauge(final String metricName, final double value, final String... tags) {}

  @Override
  public void histogram(final String metricName, final long value, final String... tags) {}

  @Override
  public void histogram(final String metricName, final double value, final String... tags) {}

  @Override
  public void distribution(String metricName, long value, String... tags) {}

  @Override
  public void distribution(String metricName, double value, String... tags) {}

  @Override
  public void serviceCheck(
      final String serviceCheckName,
      final String status,
      final String message,
      final String... tags) {}

  @Override
  public void error(Exception error) {}

  @Override
  public int getErrorCount() {
    return 0;
  }

  @Override
  public void close() {}
}

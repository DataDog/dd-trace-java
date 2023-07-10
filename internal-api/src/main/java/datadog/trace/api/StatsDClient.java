package datadog.trace.api;

import java.io.Closeable;

public interface StatsDClient extends Closeable {
  StatsDClient NO_OP = new NoOpStatsDClient();

  void incrementCounter(String metricName, String... tags);

  void count(String metricName, long delta, String... tags);

  void gauge(String metricName, long value, String... tags);

  void gauge(String metricName, double value, String... tags);

  void histogram(String metricName, long value, String... tags);

  void histogram(String metricName, double value, String... tags);

  void distribution(String metricName, long value, String... tags);

  void distribution(String metricName, double value, String... tags);

  void serviceCheck(String serviceCheckName, String status, String message, String... tags);

  void error(Exception error);

  int getErrorCount();

  @Override
  void close();
}

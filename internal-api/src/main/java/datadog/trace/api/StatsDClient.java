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

  /**
   * Record a statsd event
   *
   * @param type the type of event (error, warning, info, success - @see Event.AlertType)
   * @param source the source of the event (e.g. java, myapp, CrashTracking, Telemetry, etc)
   * @param eventName the name of the event (or title)
   * @param message the message of the event
   * @param tags the tags to attach to the event
   */
  default void recordEvent(
      String type, String source, String eventName, String message, String... tags) {}

  @Override
  void close();
}

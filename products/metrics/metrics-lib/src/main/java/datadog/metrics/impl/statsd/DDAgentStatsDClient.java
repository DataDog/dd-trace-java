package datadog.metrics.impl.statsd;

import com.timgroup.statsd.Event;
import com.timgroup.statsd.ServiceCheck;
import datadog.metrics.statsd.StatsDClient;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DDAgentStatsDClient implements StatsDClient {
  private static final Logger log = LoggerFactory.getLogger(DDAgentStatsDClient.class);
  private final DDAgentStatsDConnection connection;
  private final Function<String, String> nameMapping;
  private final Function<String[], String[]> tagMapping;

  DDAgentStatsDClient(
      final DDAgentStatsDConnection connection,
      final Function<String, String> nameMapping,
      final Function<String[], String[]> tagMapping) {
    this.connection = connection;
    this.nameMapping = nameMapping;
    this.tagMapping = tagMapping;

    connection.acquire();
  }

  @Override
  public void incrementCounter(final String metricName, final String... tags) {
    connection.statsd.count(nameMapping.apply(metricName), 1L, tagMapping.apply(tags));
  }

  @Override
  public void count(final String metricName, final long value, final String... tags) {
    connection.statsd.count(nameMapping.apply(metricName), value, tagMapping.apply(tags));
  }

  @Override
  public void gauge(final String metricName, final long value, final String... tags) {
    connection.statsd.recordGaugeValue(
        nameMapping.apply(metricName), value, tagMapping.apply(tags));
  }

  @Override
  public void gauge(final String metricName, final double value, final String... tags) {
    connection.statsd.recordGaugeValue(
        nameMapping.apply(metricName), value, tagMapping.apply(tags));
  }

  @Override
  public void histogram(final String metricName, final long value, final String... tags) {
    connection.statsd.recordHistogramValue(
        nameMapping.apply(metricName), value, tagMapping.apply(tags));
  }

  @Override
  public void histogram(final String metricName, final double value, final String... tags) {
    connection.statsd.recordHistogramValue(
        nameMapping.apply(metricName), value, tagMapping.apply(tags));
  }

  @Override
  public void distribution(String metricName, long value, String... tags) {
    connection.statsd.recordDistributionValue(
        nameMapping.apply(metricName), value, tagMapping.apply(tags));
  }

  @Override
  public void distribution(String metricName, double value, String... tags) {
    connection.statsd.recordDistributionValue(
        nameMapping.apply(metricName), value, tagMapping.apply(tags));
  }

  @Override
  public void serviceCheck(
      final String serviceCheckName,
      final String status,
      final String message,
      final String... tags) {

    ServiceCheck serviceCheck =
        ServiceCheck.builder()
            .withName(nameMapping.apply(serviceCheckName))
            .withStatus(serviceCheckStatus(status))
            .withMessage(message)
            .withTags(tagMapping.apply(tags))
            .build();

    connection.statsd.recordServiceCheckRun(serviceCheck);
  }

  @Override
  public void recordEvent(
      String type, String source, String eventName, String message, String... tags) {
    Event.AlertType alertType = Event.AlertType.valueOf(type.toUpperCase());
    Event.Builder eventBuilder =
        Event.builder()
            .withTitle(eventName)
            .withText(message)
            .withSourceTypeName(source)
            .withDate(System.currentTimeMillis())
            .withAlertType(alertType);
    connection.statsd.recordEvent(eventBuilder.build(), tagMapping.apply(tags));
  }

  static ServiceCheck.Status serviceCheckStatus(final String status) {
    switch (status) {
      case "OK":
        return ServiceCheck.Status.OK;
      case "WARN":
      case "WARNING":
        return ServiceCheck.Status.WARNING;
      case "CRITICAL":
      case "ERROR":
        return ServiceCheck.Status.CRITICAL;
      default:
        return ServiceCheck.Status.UNKNOWN;
    }
  }

  @Override
  public void error(final Exception error) {
    connection.handle(error);
  }

  @Override
  public int getErrorCount() {
    return connection.getErrorCount();
  }

  @Override
  public void close() {
    connection.release();
  }
}

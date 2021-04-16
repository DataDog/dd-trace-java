package datadog.trace.agent.jmxfetch;

import datadog.trace.api.StatsDClient;
import org.datadog.jmxfetch.Instance;
import org.datadog.jmxfetch.JmxAttribute;
import org.datadog.jmxfetch.reporter.LoggingErrorHandler;
import org.datadog.jmxfetch.reporter.Reporter;

/** Based on {@link org.datadog.jmxfetch.reporter.StatsdReporter}. */
public final class AgentStatsdReporter extends Reporter {

  private final StatsDClient statsd;

  public AgentStatsdReporter(final StatsDClient statsd) {
    this.statsd = statsd;
    this.handler = new ErrorHandler();
  }

  @Override
  protected void sendMetricPoint(
      final String metricType, final String metricName, final double value, final String[] tags) {
    if ("monotonic_count".equals(metricType)) {
      statsd.count(metricName, (long) value, tags);
    } else if ("histogram".equals(metricType)) {
      statsd.histogram(metricName, value, tags);
    } else { // JMXFetch treats everything else as a gauge
      statsd.gauge(metricName, value, tags);
    }
  }

  @Override
  protected void doSendServiceCheck(
      final String serviceCheckName,
      final String status,
      final String message,
      final String[] tags) {
    statsd.serviceCheck(serviceCheckName, status, message, tags);
  }

  @Override
  public void displayMetricReached() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void displayMatchingAttributeName(
      final JmxAttribute jmxAttribute, final int rank, final int limit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void displayNonMatchingAttributeName(final JmxAttribute jmxAttribute) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void displayInstanceName(final Instance instance) {
    throw new UnsupportedOperationException();
  }

  /** Handler that delegates to the shared statsd connection for error tracking purposes. */
  final class ErrorHandler extends LoggingErrorHandler {
    @Override
    public void handle(final Exception error) {
      statsd.error(error);
    }

    @Override
    public int getErrors() {
      return statsd.getErrorCount();
    }
  }
}

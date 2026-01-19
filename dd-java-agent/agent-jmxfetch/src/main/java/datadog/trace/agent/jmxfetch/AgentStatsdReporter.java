package datadog.trace.agent.jmxfetch;

import datadog.metrics.statsd.StatsDClient;
import datadog.trace.api.flare.TracerFlare;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.zip.ZipOutputStream;
import org.datadog.jmxfetch.Instance;
import org.datadog.jmxfetch.JmxAttribute;
import org.datadog.jmxfetch.reporter.LoggingErrorHandler;
import org.datadog.jmxfetch.reporter.Reporter;

/** Based on {@link org.datadog.jmxfetch.reporter.StatsdReporter}. */
public final class AgentStatsdReporter extends Reporter implements TracerFlare.Reporter {

  private final StatsDClient statsd;

  private volatile Map<String, Double> history;

  public AgentStatsdReporter(final StatsDClient statsd) {
    this.statsd = statsd;
    this.handler = new ErrorHandler();
  }

  @Override
  protected void sendMetricPoint(
      final String metricType, final String metricName, final double value, final String[] tags) {
    Map<String, Double> h = history;
    if (null != h) {
      // preparing for tracer-flare, record JMXFetch metrics as they're reported
      h.put(metricName, value);
    }
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

  @Override
  public void prepareForFlare() {
    history = new ConcurrentSkipListMap<>();
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    Map<String, Double> h = history;
    if (null != h) {
      // report any JMXFetch metrics recorded while preparing for tracer-flare
      StringBuilder buf = new StringBuilder();
      NumberFormat nf = NumberFormat.getInstance(Locale.ROOT);
      for (Map.Entry<String, Double> metric : h.entrySet()) {
        buf.append(metric.getKey()).append('=').append(nf.format(metric.getValue())).append('\n');
      }
      TracerFlare.addText(zip, "jmxfetch.txt", buf.toString());
    }
  }

  @Override
  public void cleanupAfterFlare() {
    history = null;
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

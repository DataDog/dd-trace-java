package datadog.metrics.impl.statsd;

import datadog.metrics.statsd.StatsDClient;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingStatsDClient implements StatsDClient {
  private static final Logger log = LoggerFactory.getLogger(LoggingStatsDClient.class);

  // logging format is based on the StatsD datagram format
  private static final String COUNT_FORMAT = "{}:{}|c{}";
  private static final String GAUGE_FORMAT = "{}:{}|g{}";
  private static final String HISTOGRAM_FORMAT = "{}:{}|h{}";
  private static final String DISTRIBUTION_FORMAT = "{}:{}|d{}";
  private static final String SERVICE_CHECK_FORMAT = "_sc|{}|{}{}{}";
  private static final String EVENT_FORMAT = "_e|{}|{}|{}|{}|{}";

  private static final DecimalFormat DECIMAL_FORMAT;

  static {
    DECIMAL_FORMAT = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    DECIMAL_FORMAT.setMaximumFractionDigits(6);
  }

  private final Function<String, String> nameMapping;
  private final Function<String[], String[]> tagMapping;

  public LoggingStatsDClient(
      final Function<String, String> nameMapping, final Function<String[], String[]> tagMapping) {
    this.nameMapping = nameMapping;
    this.tagMapping = tagMapping;
  }

  @Override
  public void incrementCounter(final String metricName, final String... tags) {
    log.info(COUNT_FORMAT, nameMapping.apply(metricName), 1, join(tagMapping.apply(tags)));
  }

  @Override
  public void count(final String metricName, final long delta, final String... tags) {
    log.info(COUNT_FORMAT, nameMapping.apply(metricName), delta, join(tagMapping.apply(tags)));
  }

  @Override
  public void gauge(final String metricName, final long value, final String... tags) {
    log.info(GAUGE_FORMAT, nameMapping.apply(metricName), value, join(tagMapping.apply(tags)));
  }

  @Override
  public void gauge(final String metricName, final double value, final String... tags) {
    log.info(
        GAUGE_FORMAT,
        nameMapping.apply(metricName),
        DECIMAL_FORMAT.format(value),
        join(tagMapping.apply(tags)));
  }

  @Override
  public void histogram(final String metricName, final long value, final String... tags) {
    log.info(HISTOGRAM_FORMAT, nameMapping.apply(metricName), value, join(tagMapping.apply(tags)));
  }

  @Override
  public void histogram(final String metricName, final double value, final String... tags) {
    log.info(
        HISTOGRAM_FORMAT,
        nameMapping.apply(metricName),
        DECIMAL_FORMAT.format(value),
        join(tagMapping.apply(tags)));
  }

  @Override
  public void distribution(String metricName, long value, String... tags) {
    log.info(
        DISTRIBUTION_FORMAT, nameMapping.apply(metricName), value, join(tagMapping.apply(tags)));
  }

  @Override
  public void distribution(String metricName, double value, String... tags) {
    log.info(
        DISTRIBUTION_FORMAT,
        nameMapping.apply(metricName),
        DECIMAL_FORMAT.format(value),
        join(tagMapping.apply(tags)));
  }

  @Override
  public void serviceCheck(
      final String serviceCheckName,
      final String status,
      final String message,
      final String... tags) {
    log.info(
        SERVICE_CHECK_FORMAT,
        nameMapping.apply(serviceCheckName),
        DDAgentStatsDClient.serviceCheckStatus(status).ordinal(),
        join(tagMapping.apply(tags)),
        null != message ? "|m:" + message : "");
  }

  @Override
  public void error(final Exception error) {}

  @Override
  public int getErrorCount() {
    return 0;
  }

  @Override
  public void recordEvent(
      String type, String source, String eventName, String message, String... tags) {
    log.info(EVENT_FORMAT, type, source, eventName, message, join(tagMapping.apply(tags)));
  }

  @Override
  public void close() {}

  private static String join(final String... tags) {
    if (null == tags || tags.length == 0) {
      return "";
    }
    StringBuilder buf = new StringBuilder("|#").append(tags[0]);
    for (int i = 1; i < tags.length; i++) {
      buf.append(',').append(tags[i]);
    }
    return buf.toString();
  }
}

package com.datadog.debugger.util;

import datadog.communication.monitor.DDAgentStatsDClientManager;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;

/** implements a StatsD client for internal debugger agent metrics */
public class DebuggerMetrics implements StatsDClient {

  private static DebuggerMetrics INSTANCE = null;

  private static final String STATSD_NAMESPACE_PREFIX = "datadog.debugger";

  private final StatsDClient statsd;

  private DebuggerMetrics(Config config) {
    if (config.isDebuggerMetricsEnabled()) {

      statsd =
          DDAgentStatsDClientManager.statsDClientManager()
              .statsDClient(
                  config.getJmxFetchStatsdHost(),
                  config.getJmxFetchStatsdPort(),
                  config.getDogStatsDNamedPipe(),
                  STATSD_NAMESPACE_PREFIX,
                  new String[0]);
    } else {
      statsd = StatsDClient.NO_OP;
    }
  }

  public static DebuggerMetrics getInstance(Config config) {
    if (INSTANCE == null) {
      INSTANCE = new DebuggerMetrics(config);
    }
    return INSTANCE;
  }

  @Override
  public void incrementCounter(String metricName, String... tags) {
    statsd.incrementCounter(metricName, tags);
  }

  @Override
  public void count(String metricName, long delta, String... tags) {
    statsd.count(metricName, delta, tags);
  }

  @Override
  public void gauge(String metricName, long value, String... tags) {
    statsd.gauge(metricName, value, tags);
  }

  @Override
  public void gauge(String metricName, double value, String... tags) {
    statsd.gauge(metricName, value, tags);
  }

  @Override
  public void histogram(String metricName, long value, String... tags) {
    statsd.histogram(metricName, value, tags);
  }

  @Override
  public void histogram(String metricName, double value, String... tags) {
    statsd.histogram(metricName, value, tags);
  }

  @Override
  public void serviceCheck(String serviceCheckName, String status, String message, String... tags) {
    statsd.serviceCheck(serviceCheckName, status, message, tags);
  }

  @Override
  public void error(Exception error) {
    statsd.error(error);
  }

  @Override
  public int getErrorCount() {
    return statsd.getErrorCount();
  }

  @Override
  public void close() {
    statsd.close();
  }
}

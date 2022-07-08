package com.datadog.debugger.agent;

import com.timgroup.statsd.StatsDClientErrorHandler;
import datadog.communication.monitor.DDAgentStatsDClientManager;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements forwarding metric probe emitted metrics to a DogStatsD endpoint */
public class StatsdMetricForwarder
    implements DebuggerContext.MetricForwarder, StatsDClientErrorHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(StatsdMetricForwarder.class);
  private static final String METRICPROBE_PREFIX = "debugger.metric.probe";

  private final StatsDClient statsd;

  public StatsdMetricForwarder(Config config) {
    statsd =
        DDAgentStatsDClientManager.statsDClientManager()
            .statsDClient(
                config.getJmxFetchStatsdHost(),
                config.getJmxFetchStatsdPort(),
                config.getDogStatsDNamedPipe(),
                METRICPROBE_PREFIX,
                new String[0]);
  }

  @Override
  public void count(String name, long delta, String[] tags) {
    statsd.count(name, delta, tags);
  }

  @Override
  public void gauge(String name, long value, String[] tags) {
    statsd.gauge(name, value, tags);
  }

  @Override
  public void histogram(String name, long value, String[] tags) {
    statsd.histogram(name, value, tags);
  }

  @Override
  public void handle(Exception exception) {
    LOGGER.warn("Error when sending metrics: ", exception);
  }
}

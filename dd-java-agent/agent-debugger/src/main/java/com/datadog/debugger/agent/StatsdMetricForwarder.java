package com.datadog.debugger.agent;

import com.datadog.debugger.sink.ProbeStatusSink;
import com.timgroup.statsd.StatsDClientErrorHandler;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.statsd.DDAgentStatsDClientManager;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements forwarding metric probe emitted metrics to a DogStatsD endpoint */
public class StatsdMetricForwarder
    implements DebuggerContext.MetricForwarder, StatsDClientErrorHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(StatsdMetricForwarder.class);
  private static final String METRICPROBE_PREFIX = "dynamic.instrumentation.metric.probe";

  private final StatsDClient statsd;
  private final ProbeStatusSink probeStatusSink;

  public StatsdMetricForwarder(Config config, ProbeStatusSink probeStatusSink) {
    statsd =
        DDAgentStatsDClientManager.statsDClientManager()
            .statsDClient(
                config.getJmxFetchStatsdHost(),
                config.getJmxFetchStatsdPort(),
                config.getDogStatsDNamedPipe(),
                METRICPROBE_PREFIX,
                new String[0]);
    this.probeStatusSink = probeStatusSink;
  }

  @Override
  public void count(String encodedProbeId, String name, long delta, String[] tags) {
    statsd.count(name, delta, tags);
    sendEmittingStatus(encodedProbeId);
  }

  @Override
  public void gauge(String encodedProbeId, String name, long value, String[] tags) {
    statsd.gauge(name, value, tags);
    sendEmittingStatus(encodedProbeId);
  }

  @Override
  public void gauge(String encodedProbeId, String name, double value, String[] tags) {
    statsd.gauge(name, value, tags);
    sendEmittingStatus(encodedProbeId);
  }

  @Override
  public void histogram(String encodedProbeId, String name, long value, String[] tags) {
    statsd.histogram(name, value, tags);
    sendEmittingStatus(encodedProbeId);
  }

  @Override
  public void histogram(String encodedProbeId, String name, double value, String[] tags) {
    statsd.histogram(name, value, tags);
    sendEmittingStatus(encodedProbeId);
  }

  @Override
  public void distribution(String encodedProbeId, String name, long value, String[] tags) {
    statsd.distribution(name, value, tags);
    sendEmittingStatus(encodedProbeId);
  }

  @Override
  public void distribution(String encodedProbeId, String name, double value, String[] tags) {
    statsd.distribution(name, value, tags);
    sendEmittingStatus(encodedProbeId);
  }

  @Override
  public void handle(Exception exception) {
    LOGGER.warn("Error when sending metrics: ", exception);
  }

  private void sendEmittingStatus(String encodedProbeId) {
    probeStatusSink.addEmitting(encodedProbeId);
  }
}

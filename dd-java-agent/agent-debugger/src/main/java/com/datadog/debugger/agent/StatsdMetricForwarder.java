package com.datadog.debugger.agent;

import com.datadog.debugger.sink.ProbeStatusSink;
import com.timgroup.statsd.StatsDClientErrorHandler;
import datadog.communication.monitor.DDAgentStatsDClientManager;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements forwarding metric probe emitted metrics to a DogStatsD endpoint */
public class StatsdMetricForwarder
    implements DebuggerContext.MetricForwarder, StatsDClientErrorHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(StatsdMetricForwarder.class);
  private static final String METRICPROBE_PREFIX = "dynamic.instrumentation.metric.probe";

  private final StatsDClient statsd;
  private final DebuggerContext.ProbeResolver probeResolver;
  private final ProbeStatusSink probeStatusSink;

  public StatsdMetricForwarder(
      Config config, DebuggerContext.ProbeResolver probeResolver, ProbeStatusSink probeStatusSink) {
    statsd =
        DDAgentStatsDClientManager.statsDClientManager()
            .statsDClient(
                config.getJmxFetchStatsdHost(),
                config.getJmxFetchStatsdPort(),
                config.getDogStatsDNamedPipe(),
                METRICPROBE_PREFIX,
                new String[0]);
    this.probeResolver = probeResolver;
    this.probeStatusSink = probeStatusSink;
  }

  @Override
  public void count(String probeId, String name, long delta, String[] tags) {
    statsd.count(name, delta, tags);
    sendEmittingStatus(probeId);
  }

  @Override
  public void gauge(String probeId, String name, long value, String[] tags) {
    statsd.gauge(name, value, tags);
    sendEmittingStatus(probeId);
  }

  @Override
  public void gauge(String probeId, String name, double value, String[] tags) {
    statsd.gauge(name, value, tags);
    sendEmittingStatus(probeId);
  }

  @Override
  public void histogram(String probeId, String name, long value, String[] tags) {
    statsd.histogram(name, value, tags);
    sendEmittingStatus(probeId);
  }

  @Override
  public void histogram(String probeId, String name, double value, String[] tags) {
    statsd.histogram(name, value, tags);
    sendEmittingStatus(probeId);
  }

  @Override
  public void distribution(String probeId, String name, long value, String[] tags) {
    statsd.distribution(name, value, tags);
    sendEmittingStatus(probeId);
  }

  @Override
  public void distribution(String probeId, String name, double value, String[] tags) {
    statsd.distribution(name, value, tags);
    sendEmittingStatus(probeId);
  }

  @Override
  public void handle(Exception exception) {
    LOGGER.warn("Error when sending metrics: ", exception);
  }

  private void sendEmittingStatus(String probeId) {
    ProbeImplementation probeImplementation = probeResolver.resolve(probeId, null);
    if (probeImplementation == null) {
      LOGGER.debug("Cannot resolve probe id: {}", probeId);
      return;
    }
    probeStatusSink.addEmitting(probeImplementation.getProbeId());
  }
}

package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import org.junit.jupiter.api.Test;

class StatsdMetricForwarderTest {
  private static final ProbeId METRIC_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);

  @Test
  void emittingCountMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.count(METRIC_ID.getId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID));
  }

  @Test
  void emittingGaugeLongMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.gauge(METRIC_ID.getId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID));
  }

  @Test
  void emittingGaugeDoubleMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.gauge(METRIC_ID.getId(), "name", 1.0, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID));
  }

  @Test
  void emittingHistogramLongMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.histogram(METRIC_ID.getId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID));
  }

  @Test
  void emittingHistogramDoubleMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.histogram(METRIC_ID.getId(), "name", 1.0, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID));
  }

  @Test
  void emittingDistributionLongMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.distribution(METRIC_ID.getId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID));
  }

  @Test
  void emittingDistributionDoubleMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.distribution(METRIC_ID.getId(), "name", 1.0, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID));
  }

  @Test
  void badResolution() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(
            Config.get(), StatsdMetricForwarderTest::resolver, probeStatusSink);
    statsdMetricForwarder.count("badId", "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink, times(0)).addEmitting(eq(METRIC_ID));
  }

  private static ProbeImplementation resolver(String probeId, Class<?> callingClass) {
    if (probeId.equals(METRIC_ID.getId())) {
      return new ProbeImplementation.NoopProbeImplementation(METRIC_ID, ProbeLocation.UNKNOWN);
    }
    return null;
  }
}

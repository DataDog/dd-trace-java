package com.datadog.debugger.agent;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.ProbeId;
import org.junit.jupiter.api.Test;

class StatsdMetricForwarderTest {
  private static final ProbeId METRIC_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);

  @Test
  void emittingCountMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.count(METRIC_ID.getEncodedId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID.getEncodedId()));
  }

  @Test
  void emittingGaugeLongMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.gauge(METRIC_ID.getEncodedId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID.getEncodedId()));
  }

  @Test
  void emittingGaugeDoubleMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.gauge(METRIC_ID.getEncodedId(), "name", 1.0, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID.getEncodedId()));
  }

  @Test
  void emittingHistogramLongMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.histogram(METRIC_ID.getEncodedId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID.getEncodedId()));
  }

  @Test
  void emittingHistogramDoubleMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.histogram(
        METRIC_ID.getEncodedId(), "name", 1.0, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID.getEncodedId()));
  }

  @Test
  void emittingDistributionLongMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.distribution(
        METRIC_ID.getEncodedId(), "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID.getEncodedId()));
  }

  @Test
  void emittingDistributionDoubleMetric() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.distribution(
        METRIC_ID.getEncodedId(), "name", 1.0, new String[] {"foo:bar"});
    verify(probeStatusSink).addEmitting(eq(METRIC_ID.getEncodedId()));
  }

  @Test
  void badResolution() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(Config.get(), probeStatusSink);
    statsdMetricForwarder.count("badId:0", "name", 1, new String[] {"foo:bar"});
    verify(probeStatusSink, times(0)).addEmitting(eq(METRIC_ID));
  }
}

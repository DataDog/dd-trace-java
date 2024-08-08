package datadog.trace.bootstrap.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DebuggerContextTest {

  @Test
  public void metric() {
    MockMetricForwarder metricForwarder = new MockMetricForwarder();
    DebuggerContext.initMetricForwarder(metricForwarder);

    final String COUNT_METRIC_NAME = "countMetric";
    DebuggerContext.metric("probeId", DebuggerContext.MetricKind.COUNT, COUNT_METRIC_NAME, 1, null);
    assertEquals(1, metricForwarder.counters.get(COUNT_METRIC_NAME));

    final String GAUGE_METRIC_NAME = "gaugeMetric";
    DebuggerContext.metric(
        "probeId", DebuggerContext.MetricKind.GAUGE, GAUGE_METRIC_NAME, 42, null);
    assertEquals(42, metricForwarder.gauges.get(GAUGE_METRIC_NAME));

    final String DOUBLE_GAUGE_METRIC_NAME = "doubleGaugeMetric";
    DebuggerContext.metric(
        "probeId", DebuggerContext.MetricKind.GAUGE, DOUBLE_GAUGE_METRIC_NAME, 3.14, null);
    assertEquals(3.14, metricForwarder.doubleGauges.get(DOUBLE_GAUGE_METRIC_NAME));

    final String HISTOGRAM_METRIC_NAME = "histogramMetric";
    DebuggerContext.metric(
        "probeId", DebuggerContext.MetricKind.HISTOGRAM, HISTOGRAM_METRIC_NAME, 42, null);
    assertEquals(42, metricForwarder.histograms.get(HISTOGRAM_METRIC_NAME));

    final String DOUBLE_HISTOGRAM_METRIC_NAME = "doubleHistogramMetric";
    DebuggerContext.metric(
        "probeId", DebuggerContext.MetricKind.HISTOGRAM, DOUBLE_HISTOGRAM_METRIC_NAME, 3.14, null);
    assertEquals(3.14, metricForwarder.doubleHistograms.get(DOUBLE_HISTOGRAM_METRIC_NAME));

    final String DISTRIBUTION_METRIC_NAME = "distributionMetric";
    DebuggerContext.metric(
        "probeId", DebuggerContext.MetricKind.DISTRIBUTION, DISTRIBUTION_METRIC_NAME, 42, null);
    assertEquals(42, metricForwarder.distributions.get(DISTRIBUTION_METRIC_NAME));

    final String DOUBLE_DISTRIBUTION_METRIC_NAME = "doubleDistributionMetric";
    DebuggerContext.metric(
        "probeId",
        DebuggerContext.MetricKind.DISTRIBUTION,
        DOUBLE_DISTRIBUTION_METRIC_NAME,
        3.14,
        null);
    assertEquals(3.14, metricForwarder.doubleDistributions.get(DOUBLE_DISTRIBUTION_METRIC_NAME));
  }

  static class MockMetricForwarder implements DebuggerContext.MetricForwarder {
    Map<String, Long> counters = new HashMap<>();
    Map<String, Long> gauges = new HashMap<>();
    Map<String, Double> doubleGauges = new HashMap<>();
    Map<String, Long> histograms = new HashMap<>();
    Map<String, Double> doubleHistograms = new HashMap<>();
    Map<String, Long> distributions = new HashMap<>();
    Map<String, Double> doubleDistributions = new HashMap<>();

    @Override
    public void count(String encodedProbeId, String name, long delta, String[] tags) {
      counters.compute(name, (key, value) -> value != null ? value + delta : delta);
    }

    @Override
    public void gauge(String encodedProbeId, String name, long value, String[] tags) {
      gauges.put(name, value);
    }

    @Override
    public void gauge(String encodedProbeId, String name, double value, String[] tags) {
      doubleGauges.put(name, value);
    }

    @Override
    public void histogram(String encodedProbeId, String name, long value, String[] tags) {
      histograms.put(name, value);
    }

    @Override
    public void histogram(String encodedProbeId, String name, double value, String[] tags) {
      doubleHistograms.put(name, value);
    }

    @Override
    public void distribution(String encodedProbeId, String name, long value, String[] tags) {
      distributions.put(name, value);
    }

    @Override
    public void distribution(String encodedProbeId, String name, double value, String[] tags) {
      doubleDistributions.put(name, value);
    }
  }
}

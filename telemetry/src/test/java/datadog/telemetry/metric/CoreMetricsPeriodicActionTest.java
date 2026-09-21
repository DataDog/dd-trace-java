package datadog.telemetry.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.metrics.SpanMetricRegistry;
import datadog.trace.api.metrics.SpanMetrics;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CoreMetricsPeriodicActionTest {

  private final TelemetryService telemetryService = mock(TelemetryService.class);
  private final CoreMetricsPeriodicAction action = new CoreMetricsPeriodicAction();
  private final SpanMetricRegistry spanMetricRegistry = SpanMetricRegistry.getInstance();

  @Test
  void testSpanMetricsWithMultipleOccurrenceEvents() {
    SpanMetrics instr1SpanMetric = spanMetricRegistry.get("instr-1");
    SpanMetrics instr2SpanMetric = spanMetricRegistry.get("instr-2");

    instr1SpanMetric.onSpanCreated();
    instr2SpanMetric.onSpanCreated();
    instr1SpanMetric.onSpanCreated();
    instr2SpanMetric.onSpanCreated();
    instr2SpanMetric.onSpanCreated();
    instr2SpanMetric.onSpanFinished();
    instr2SpanMetric.onSpanFinished();
    instr2SpanMetric.onSpanFinished();
    instr2SpanMetric.onSpanFinished();

    action.collector().prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(3)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Metric> metrics = captor.getAllValues();
    assertMetric(metrics, "spans_created", "instr-1", 2);
    assertMetric(metrics, "spans_created", "instr-2", 3);
    assertMetric(metrics, "spans_finished", "instr-2", 4);
  }

  @Test
  void testSpanMetricsWithInterleavedEvents() {
    SpanMetrics instr1SpanMetric = spanMetricRegistry.get("instr-1");
    SpanMetrics instr2SpanMetric = spanMetricRegistry.get("instr-2");
    SpanMetrics instr3SpanMetric = spanMetricRegistry.get("instr-3");
    SpanMetrics instrASpanMetric = spanMetricRegistry.get("instr-a");
    SpanMetrics instrBSpanMetric = spanMetricRegistry.get("instr-b");

    instr1SpanMetric.onSpanCreated();
    instr2SpanMetric.onSpanCreated();
    instr3SpanMetric.onSpanCreated();
    instr3SpanMetric.onSpanFinished();
    instrASpanMetric.onSpanCreated();
    instrBSpanMetric.onSpanCreated();
    instrBSpanMetric.onSpanFinished();
    instr2SpanMetric.onSpanFinished();
    instrASpanMetric.onSpanFinished();
    instr1SpanMetric.onSpanFinished();

    action.collector().prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(10)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Metric> metrics = captor.getAllValues();
    assertMetric(metrics, "spans_created", "instr-1", 1);
    assertMetric(metrics, "spans_created", "instr-2", 1);
    assertMetric(metrics, "spans_created", "instr-3", 1);
    assertMetric(metrics, "spans_finished", "instr-3", 1);
    assertMetric(metrics, "spans_created", "instr-a", 1);
    assertMetric(metrics, "spans_created", "instr-b", 1);
    assertMetric(metrics, "spans_finished", "instr-b", 1);
    assertMetric(metrics, "spans_finished", "instr-2", 1);
    assertMetric(metrics, "spans_finished", "instr-a", 1);
    assertMetric(metrics, "spans_finished", "instr-1", 1);
  }

  @Test
  void testSpanMetrics() {
    SpanMetrics instr1SpanMetric = spanMetricRegistry.get("instr-1");
    SpanMetrics instr2SpanMetric = spanMetricRegistry.get("instr-2");
    SpanMetrics instr3SpanMetric = spanMetricRegistry.get("instr-3");
    SpanMetrics instrASpanMetric = spanMetricRegistry.get("instr-a");
    SpanMetrics instrBSpanMetric = spanMetricRegistry.get("instr-b");

    instr1SpanMetric.onSpanCreated();
    instr2SpanMetric.onSpanCreated();
    instr3SpanMetric.onSpanCreated();
    instr3SpanMetric.onSpanFinished();
    instrASpanMetric.onSpanCreated();
    instrBSpanMetric.onSpanCreated();
    instrBSpanMetric.onSpanFinished();
    instr2SpanMetric.onSpanFinished();
    instrASpanMetric.onSpanFinished();
    instr1SpanMetric.onSpanFinished();

    action.collector().prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(10)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Metric> metrics = captor.getAllValues();
    assertMetric(metrics, "spans_created", "instr-1", 1);
    assertMetric(metrics, "spans_created", "instr-2", 1);
    assertMetric(metrics, "spans_created", "instr-3", 1);
    assertMetric(metrics, "spans_finished", "instr-3", 1);
    assertMetric(metrics, "spans_created", "instr-a", 1);
    assertMetric(metrics, "spans_created", "instr-b", 1);
    assertMetric(metrics, "spans_finished", "instr-b", 1);
    assertMetric(metrics, "spans_finished", "instr-2", 1);
    assertMetric(metrics, "spans_finished", "instr-a", 1);
    assertMetric(metrics, "spans_finished", "instr-1", 1);
  }

  /**
   * MetricPeriodicAction aggregates metrics through a HashMap, so the emission order is not
   * guaranteed; match each expected metric by its metric name and instrumentation tag rather than
   * by capture position.
   */
  private void assertMetric(
      List<Metric> metrics, String metricName, String instrumentationName, long count) {
    List<String> expectedTags = Arrays.asList("integration_name:" + instrumentationName);
    List<Metric> matches =
        metrics.stream()
            .filter(metric -> metric.getMetric().equals(metricName))
            .filter(metric -> expectedTags.equals(metric.getTags()))
            .collect(Collectors.toList());
    assertEquals(
        1, matches.size(), "expected exactly one match for " + metricName + " " + expectedTags);

    Metric metric = matches.get(0);
    assertEquals("tracers", metric.getNamespace());
    assertEquals(true, metric.getCommon());
    assertEquals(1, metric.getPoints().size());
    assertEquals(2, metric.getPoints().get(0).size());
    assertEquals(count, metric.getPoints().get(0).get(1).longValue());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
  }
}

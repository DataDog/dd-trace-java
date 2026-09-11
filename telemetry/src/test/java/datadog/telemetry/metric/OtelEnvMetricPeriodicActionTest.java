package datadog.telemetry.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.OtelEnvMetricCollectorImpl;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OtelEnvMetricPeriodicActionTest {

  private final TelemetryService telemetryService = mock(TelemetryService.class);
  private final OtelEnvMetricPeriodicAction action = new OtelEnvMetricPeriodicAction();
  private final OtelEnvMetricCollectorImpl collector = OtelEnvMetricCollectorImpl.getInstance();

  @Test
  void testOtelEnvVarHidingMetric() {
    collector.setHidingOtelEnvVarMetric("otel_service_name", "dd_service_name");
    collector.prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(1)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    Metric metric = captor.getValue();
    assertEquals("tracers", metric.getNamespace());
    assertEquals("otel.env.hiding", metric.getMetric());
    assertEquals(1L, metric.getPoints().get(0).get(1).longValue());
    assertEquals(
        Arrays.asList("config_opentelemetry:otel_service_name", "config_datadog:dd_service_name"),
        metric.getTags());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
  }

  @Test
  void testOtelEnvVarUnsupportedMetric() {
    collector.setUnsupportedOtelEnvVarMetric("unsupported_env_var");
    collector.prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(1)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    Metric metric = captor.getValue();
    assertEquals("tracers", metric.getNamespace());
    assertEquals("otel.env.unsupported", metric.getMetric());
    assertEquals(1L, metric.getPoints().get(0).get(1).longValue());
    assertEquals(Arrays.asList("config_opentelemetry:unsupported_env_var"), metric.getTags());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
  }

  @Test
  void testOtelEnvVarInvalidMetric() {
    collector.setInvalidOtelEnvVarMetric("otel_env_var", "dd_env_var");
    collector.prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(1)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    Metric metric = captor.getValue();
    assertEquals("tracers", metric.getNamespace());
    assertEquals("otel.env.invalid", metric.getMetric());
    assertEquals(1L, metric.getPoints().get(0).get(1).longValue());
    assertEquals(
        Arrays.asList("config_opentelemetry:otel_env_var", "config_datadog:dd_env_var"),
        metric.getTags());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
  }

  @Test
  void testOtelEnvVarMultipleMetrics() {
    collector.setInvalidOtelEnvVarMetric("otel_env_var", "dd_env_var");
    collector.setInvalidOtelEnvVarMetric("otel_env_var2", "dd_env_var2");
    collector.setHidingOtelEnvVarMetric("otel_service_name", "dd_service_name");
    collector.setUnsupportedOtelEnvVarMetric("unsupported_env_var");
    collector.prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(4)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    // MetricPeriodicAction aggregates metrics through a HashMap, so the emission order is not
    // guaranteed; match each expected metric by its tags rather than by capture position.
    List<Metric> metrics = captor.getAllValues();
    assertMetric(
        metrics,
        "otel.env.invalid",
        Arrays.asList("config_opentelemetry:otel_env_var", "config_datadog:dd_env_var"));
    assertMetric(
        metrics,
        "otel.env.invalid",
        Arrays.asList("config_opentelemetry:otel_env_var2", "config_datadog:dd_env_var2"));
    assertMetric(
        metrics,
        "otel.env.hiding",
        Arrays.asList("config_opentelemetry:otel_service_name", "config_datadog:dd_service_name"));
    assertMetric(
        metrics, "otel.env.unsupported", Arrays.asList("config_opentelemetry:unsupported_env_var"));
  }

  private void assertMetric(List<Metric> metrics, String metricName, List<String> tags) {
    List<Metric> matches =
        metrics.stream()
            .filter(metric -> metric.getMetric().equals(metricName))
            .filter(metric -> tags.equals(metric.getTags()))
            .collect(Collectors.toList());
    assertEquals(1, matches.size(), "expected exactly one match for " + metricName + " " + tags);

    Metric metric = matches.get(0);
    assertEquals("tracers", metric.getNamespace());
    assertEquals(1L, metric.getPoints().get(0).get(1).longValue());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
  }
}

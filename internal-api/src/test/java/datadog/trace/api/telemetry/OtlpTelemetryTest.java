package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtlpTelemetryTest {
  private final OtlpTelemetry collector = OtlpTelemetry.getInstance();

  @BeforeEach
  void drainStaleMetrics() {
    collector.prepareMetrics();
    collector.drain();
  }

  @Test
  void onTracesExportAttemptQueuesCountMetricWithTags() {
    collector.onTracesExportAttempt();
    collector.prepareMetrics();

    Collection<OtlpTelemetry.OtlpMetric> metrics = collector.drain();

    assertEquals(1, metrics.size());
    OtlpTelemetry.OtlpMetric metric = metrics.iterator().next();
    assertEquals("tracers", metric.namespace);
    assertEquals("otel.traces_export_attempts", metric.metricName);
    assertEquals("count", metric.type);
    assertEquals(1L, metric.value);
    assertEquals(2, metric.tags.size());
    assertTrue(metric.tags.contains("protocol:http"));
    assertTrue(metric.tags.contains("encoding:protobuf"));
  }

  @Test
  void tracesExportMetricsUseExpectedNames() {
    collector.onTracesExportAttempt();
    collector.onTracesExportAttempt();
    collector.onTracesExportComplete(true);
    collector.onTracesExportComplete(false);
    collector.prepareMetrics();

    Map<String, Number> valuesByName = drainToMap();

    assertEquals(3, valuesByName.size());
    assertEquals(2L, valuesByName.get("otel.traces_export_attempts"));
    assertEquals(1L, valuesByName.get("otel.traces_export_successes"));
    assertEquals(1L, valuesByName.get("otel.traces_export_failures"));
  }

  @Test
  void onMetricsExportAttemptQueuesCountMetricWithTags() {
    collector.onMetricsExportAttempt();
    collector.prepareMetrics();

    Collection<OtlpTelemetry.OtlpMetric> metrics = collector.drain();

    assertEquals(1, metrics.size());
    OtlpTelemetry.OtlpMetric metric = metrics.iterator().next();
    assertEquals("tracers", metric.namespace);
    assertEquals("otel.metrics_export_attempts", metric.metricName);
    assertEquals("count", metric.type);
    assertEquals(1L, metric.value);
    assertEquals(2, metric.tags.size());
    assertTrue(metric.tags.contains("protocol:http"));
    assertTrue(metric.tags.contains("encoding:protobuf"));
  }

  @Test
  void metricsExportMetricsUseExpectedNames() {
    collector.onMetricsExportAttempt();
    collector.onMetricsExportAttempt();
    collector.onMetricsExportComplete(true);
    collector.onMetricsExportComplete(false);
    collector.prepareMetrics();

    Map<String, Number> valuesByName = drainToMap();

    assertEquals(3, valuesByName.size());
    assertEquals(2L, valuesByName.get("otel.metrics_export_attempts"));
    assertEquals(1L, valuesByName.get("otel.metrics_export_successes"));
    assertEquals(1L, valuesByName.get("otel.metrics_export_failures"));
  }

  @Test
  void onLogRecordsSubmittedQueuesCountWithGivenValue() {
    collector.onLogRecordsSubmitted(5);
    collector.prepareMetrics();

    Collection<OtlpTelemetry.OtlpMetric> metrics = collector.drain();

    assertEquals(1, metrics.size());
    OtlpTelemetry.OtlpMetric metric = metrics.iterator().next();
    assertEquals("otel.log_records", metric.metricName);
    assertEquals(5L, metric.value);
    assertTrue(metric.tags.contains("protocol:http"));
    assertTrue(metric.tags.contains("encoding:protobuf"));
  }

  @Test
  void onLogRecordsSubmittedIgnoresNonPositiveCounts() {
    collector.onLogRecordsSubmitted(0);
    collector.onLogRecordsSubmitted(-1);

    assertTrue(collector.drain().isEmpty());
  }

  @Test
  void drainReturnsEmptyCollectionWhenNoMetricsQueued() {
    assertTrue(collector.drain().isEmpty());
  }

  private Map<String, Number> drainToMap() {
    Map<String, Number> valuesByName = new HashMap<>();
    for (OtlpTelemetry.OtlpMetric metric : collector.drain()) {
      valuesByName.put(metric.metricName, metric.value);
    }
    return valuesByName;
  }
}

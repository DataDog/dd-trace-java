package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtlpTelemetryTest {
  private final OtlpTelemetry collector = OtlpTelemetry.getInstance();

  @BeforeEach
  void drainStaleMetrics() {
    collector.drain();
  }

  @Test
  void onMetricsExportAttemptQueuesCountMetricWithTags() {
    collector.onMetricsExportAttempt();

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
    collector.onMetricsExportSuccess();
    collector.onMetricsExportFailure();

    Collection<OtlpTelemetry.OtlpMetric> metrics = collector.drain();

    assertEquals(3, metrics.size());
    assertTrue(metrics.stream().anyMatch(m -> m.metricName.equals("otel.metrics_export_attempts")));
    assertTrue(
        metrics.stream().anyMatch(m -> m.metricName.equals("otel.metrics_export_successes")));
    assertTrue(metrics.stream().anyMatch(m -> m.metricName.equals("otel.metrics_export_failures")));
  }

  @Test
  void onLogRecordsSubmittedQueuesCountWithGivenValue() {
    collector.onLogRecordsSubmitted(5);

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
}

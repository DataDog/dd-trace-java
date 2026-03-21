package datadog.trace.core.baggage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.metrics.BaggageMetrics;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BaggagePropagatorTelemetryTest {

  @Test
  void shouldDirectlyIncrementBaggageMetrics() {
    BaggageMetrics baggageMetrics = BaggageMetrics.getInstance();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    baggageMetrics.onBaggageInjected();
    collector.prepareMetrics();
    Collection<CoreMetricCollector.CoreMetric> metrics = collector.drain();

    MetricCollector.Metric baggageMetric =
        metrics.stream()
            .filter(m -> "context_header_style.injected".equals(m.metricName))
            .findFirst()
            .orElse(null);
    assertNotNull(baggageMetric);
    assertTrue(baggageMetric.value.longValue() >= 1);
    assertTrue(baggageMetric.tags.contains("header_style:baggage"));
  }

  @Test
  void shouldIncrementTelemetryCounterWhenBaggageIsSuccessfullyExtracted() {
    Config config = mock(Config.class);
    when(config.isBaggageExtract()).thenReturn(true);
    when(config.isBaggageInject()).thenReturn(true);
    when(config.getTraceBaggageMaxItems()).thenReturn(64);
    when(config.getTraceBaggageMaxBytes()).thenReturn(8192);

    BaggagePropagator propagator = new BaggagePropagator(config);
    Context context = Context.root();
    Map<String, String> carrier = new HashMap<>();
    carrier.put("baggage", "key1=value1,key2=value2");

    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    propagator.extract(context, carrier, (map, consumer) -> map.forEach(consumer));
    collector.prepareMetrics();
    Collection<CoreMetricCollector.CoreMetric> metrics = collector.drain();

    MetricCollector.Metric baggageMetric =
        metrics.stream()
            .filter(m -> "context_header_style.extracted".equals(m.metricName))
            .findFirst()
            .orElse(null);
    assertNotNull(baggageMetric);
    assertTrue(baggageMetric.value.longValue() >= 1);
    assertTrue(baggageMetric.tags.contains("header_style:baggage"));
  }

  @Test
  void shouldDirectlyIncrementAllBaggageMetrics() {
    BaggageMetrics baggageMetrics = BaggageMetrics.getInstance();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    baggageMetrics.onBaggageInjected();
    baggageMetrics.onBaggageMalformed();
    baggageMetrics.onBaggageTruncatedByByteLimit();
    baggageMetrics.onBaggageTruncatedByItemLimit();
    collector.prepareMetrics();
    Collection<CoreMetricCollector.CoreMetric> metrics = collector.drain();

    MetricCollector.Metric injectedMetric =
        metrics.stream()
            .filter(m -> "context_header_style.injected".equals(m.metricName))
            .findFirst()
            .orElse(null);
    assertNotNull(injectedMetric);
    assertEquals(1L, injectedMetric.value.longValue());
    assertTrue(injectedMetric.tags.contains("header_style:baggage"));

    MetricCollector.Metric malformedMetric =
        metrics.stream()
            .filter(m -> "context_header_style.malformed".equals(m.metricName))
            .findFirst()
            .orElse(null);
    assertNotNull(malformedMetric);
    assertEquals(1L, malformedMetric.value.longValue());
    assertTrue(malformedMetric.tags.contains("header_style:baggage"));

    MetricCollector.Metric bytesTruncatedMetric =
        metrics.stream()
            .filter(
                m ->
                    "context_header.truncated".equals(m.metricName)
                        && m.tags.contains("truncation_reason:baggage_byte_count_exceeded"))
            .findFirst()
            .orElse(null);
    assertNotNull(bytesTruncatedMetric);
    assertEquals(1L, bytesTruncatedMetric.value.longValue());

    MetricCollector.Metric itemsTruncatedMetric =
        metrics.stream()
            .filter(
                m ->
                    "context_header.truncated".equals(m.metricName)
                        && m.tags.contains("truncation_reason:baggage_item_count_exceeded"))
            .findFirst()
            .orElse(null);
    assertNotNull(itemsTruncatedMetric);
    assertEquals(1L, itemsTruncatedMetric.value.longValue());
  }

  @Test
  void shouldNotIncrementTelemetryCounterWhenBaggageExtractionFails() {
    Config config = mock(Config.class);
    when(config.isBaggageExtract()).thenReturn(true);
    when(config.isBaggageInject()).thenReturn(true);
    when(config.getTraceBaggageMaxItems()).thenReturn(64);
    when(config.getTraceBaggageMaxBytes()).thenReturn(8192);

    BaggagePropagator propagator = new BaggagePropagator(config);
    Context context = Context.root();
    Map<String, String> carrier = new HashMap<>(); // No baggage header

    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    propagator.extract(context, carrier, (map, consumer) -> map.forEach(consumer));
    collector.prepareMetrics();
    Collection<CoreMetricCollector.CoreMetric> metrics = collector.drain();

    List<CoreMetricCollector.CoreMetric> foundMetrics =
        metrics.stream()
            .filter(m -> m.metricName.startsWith("context_header_style."))
            .collect(Collectors.toList());
    assertTrue(foundMetrics.isEmpty()); // No extraction occurred, so no metrics should be created
  }
}

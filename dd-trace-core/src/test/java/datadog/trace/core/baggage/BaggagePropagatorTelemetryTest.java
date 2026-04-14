package datadog.trace.core.baggage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.api.Config;
import datadog.trace.api.metrics.BaggageMetrics;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.test.util.Flaky;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BaggagePropagatorTelemetryTest {

  private static final CarrierVisitor<Map<String, String>> MAP_VISITOR = Map::forEach;

  @Test
  void shouldDirectlyIncrementBaggageMetrics() {
    BaggageMetrics baggageMetrics = BaggageMetrics.getInstance();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    baggageMetrics.onBaggageInjected();
    collector.prepareMetrics();
    Collection<CoreMetricCollector.CoreMetric> metrics = collector.drain();

    CoreMetricCollector.CoreMetric baggageMetric =
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
    Map<String, String> carrier = Collections.singletonMap("baggage", "key1=value1,key2=value2");
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    propagator.extract(context, carrier, MAP_VISITOR);
    collector.prepareMetrics();
    Collection<CoreMetricCollector.CoreMetric> metrics = collector.drain();

    CoreMetricCollector.CoreMetric baggageMetric =
        metrics.stream()
            .filter(m -> "context_header_style.extracted".equals(m.metricName))
            .findFirst()
            .orElse(null);
    assertNotNull(baggageMetric);
    assertTrue(baggageMetric.value.longValue() >= 1);
    assertTrue(baggageMetric.tags.contains("header_style:baggage"));
  }

  @Flaky
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

    CoreMetricCollector.CoreMetric injectedMetric =
        metrics.stream()
            .filter(m -> "context_header_style.injected".equals(m.metricName))
            .findFirst()
            .orElse(null);
    assertNotNull(injectedMetric);
    assertEquals(1, injectedMetric.value.longValue());
    assertTrue(injectedMetric.tags.contains("header_style:baggage"));

    CoreMetricCollector.CoreMetric malformedMetric =
        metrics.stream()
            .filter(m -> "context_header_style.malformed".equals(m.metricName))
            .findFirst()
            .orElse(null);
    assertNotNull(malformedMetric);
    assertEquals(1, malformedMetric.value.longValue());
    assertTrue(malformedMetric.tags.contains("header_style:baggage"));

    CoreMetricCollector.CoreMetric bytesTruncatedMetric =
        metrics.stream()
            .filter(
                m ->
                    "context_header.truncated".equals(m.metricName)
                        && m.tags.contains("truncation_reason:baggage_byte_count_exceeded"))
            .findFirst()
            .orElse(null);
    assertNotNull(bytesTruncatedMetric);
    assertEquals(1, bytesTruncatedMetric.value.longValue());

    CoreMetricCollector.CoreMetric itemsTruncatedMetric =
        metrics.stream()
            .filter(
                m ->
                    "context_header.truncated".equals(m.metricName)
                        && m.tags.contains("truncation_reason:baggage_item_count_exceeded"))
            .findFirst()
            .orElse(null);
    assertNotNull(itemsTruncatedMetric);
    assertEquals(1, itemsTruncatedMetric.value.longValue());
  }

  @Flaky
  @Test
  void shouldNotIncrementTelemetryCounterWhenBaggageExtractionFails() {
    Config config = mock(Config.class);
    when(config.isBaggageExtract()).thenReturn(true);
    when(config.isBaggageInject()).thenReturn(true);
    when(config.getTraceBaggageMaxItems()).thenReturn(64);
    when(config.getTraceBaggageMaxBytes()).thenReturn(8192);
    BaggagePropagator propagator = new BaggagePropagator(config);
    Context context = Context.root();
    Map<String, String> carrier = Collections.emptyMap();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    propagator.extract(context, carrier, MAP_VISITOR);
    collector.prepareMetrics();
    Collection<CoreMetricCollector.CoreMetric> metrics = collector.drain();

    List<CoreMetricCollector.CoreMetric> foundMetrics =
        metrics.stream()
            .filter(m -> m.metricName.startsWith("context_header_style."))
            .collect(Collectors.toList());
    assertTrue(foundMetrics.isEmpty());
  }
}

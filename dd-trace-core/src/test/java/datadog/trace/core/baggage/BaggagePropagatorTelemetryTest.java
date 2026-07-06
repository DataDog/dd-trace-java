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
import datadog.trace.api.telemetry.CoreMetricCollector.CoreMetric;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BaggagePropagatorTelemetryTest {

  private static final CarrierVisitor<Map<String, String>> MAP_VISITOR = Map::forEach;

  @BeforeEach
  void setup() {
    // Drain any metrics accumulated by other tests
    CoreMetricCollector.getInstance().prepareMetrics();
    CoreMetricCollector.getInstance().drain();
  }

  @Test
  void shouldDirectlyIncrementBaggageMetrics() {
    BaggageMetrics baggageMetrics = BaggageMetrics.getInstance();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    baggageMetrics.onBaggageInjected();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    CoreMetric baggageMetric = metricFromName(metrics, "context_header_style.injected");
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
    Collection<CoreMetric> metrics = collector.drain();

    CoreMetric baggageMetric = metricFromName(metrics, "context_header_style.extracted");
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
    baggageMetrics.onBaggageTruncatedByInjectByteLimit();
    baggageMetrics.onBaggageTruncatedByInjectItemLimit();
    baggageMetrics.onBaggageTruncatedByExtractByteLimit();
    baggageMetrics.onBaggageTruncatedByExtractItemLimit();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    CoreMetric injectedMetric = metricFromName(metrics, "context_header_style.injected");
    assertNotNull(injectedMetric);
    assertEquals(1, injectedMetric.value.longValue());
    assertTrue(injectedMetric.tags.contains("header_style:baggage"));

    CoreMetric malformedMetric = metricFromName(metrics, "context_header_style.malformed");
    assertNotNull(malformedMetric);
    assertEquals(1, malformedMetric.value.longValue());
    assertTrue(malformedMetric.tags.contains("header_style:baggage"));

    CoreMetric bytesTruncatedMetric =
        truncateMetricFromName(metrics, "truncation_reason:baggage_byte_count_exceeded");
    assertNotNull(bytesTruncatedMetric);
    assertEquals(1, bytesTruncatedMetric.value.longValue());

    CoreMetric itemsTruncatedMetric =
        truncateMetricFromName(metrics, "truncation_reason:baggage_item_count_exceeded");
    assertNotNull(itemsTruncatedMetric);
    assertEquals(1, itemsTruncatedMetric.value.longValue());

    CoreMetric extractBytesTruncatedMetric =
        truncateMetricFromName(metrics, "truncation_reason:baggage_extract_byte_exceeded");
    assertNotNull(extractBytesTruncatedMetric);
    assertEquals(1, extractBytesTruncatedMetric.value.longValue());

    CoreMetric extractItemsTruncatedMetric =
        truncateMetricFromName(metrics, "truncation_reason:baggage_extract_item_exceeded");
    assertNotNull(extractItemsTruncatedMetric);
    assertEquals(1, extractItemsTruncatedMetric.value.longValue());
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
    Map<String, String> carrier = Collections.emptyMap();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    propagator.extract(context, carrier, MAP_VISITOR);
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    List<CoreMetric> foundMetrics =
        metrics.stream()
            .filter(m -> m.metricName.startsWith("context_header_style."))
            .collect(Collectors.toList());
    assertTrue(foundMetrics.isEmpty());
  }

  private static @Nullable CoreMetric metricFromName(Collection<CoreMetric> metrics, String name) {
    return metrics.stream().filter(m -> name.equals(m.metricName)).findFirst().orElse(null);
  }

  private static @Nullable CoreMetric truncateMetricFromName(
      Collection<CoreMetric> metrics, String name) {
    return metrics.stream()
        .filter(m -> "context_header.truncated".equals(m.metricName) && m.tags.contains(name))
        .findFirst()
        .orElse(null);
  }
}

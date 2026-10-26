package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.metrics.BaggageMetrics;
import datadog.trace.api.telemetry.CoreMetricCollector.CoreMetric;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class CoreMetricCollectorBaggageTest {

  private final CoreMetricCollector collector = CoreMetricCollector.getInstance();
  private final BaggageMetrics baggageMetrics = BaggageMetrics.getInstance();

  @Test
  void shouldCollectBaggageExtractionMetricsWithHeaderStyleTag() {
    // when
    baggageMetrics.onBaggageExtracted();
    baggageMetrics.onBaggageExtracted();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    CoreMetric baggageMetric = findMetric(metrics, "context_header_style.extracted");
    assertNotNull(baggageMetric);
    assertEquals("tracers", baggageMetric.namespace);
    assertEquals("count", baggageMetric.type);
    assertEquals(2L, baggageMetric.value.longValue());
    assertTrue(baggageMetric.tags.contains("header_style:baggage"));
    assertTrue(baggageMetric.common);
  }

  @Test
  void shouldCollectBaggageInjectionMetricsWithHeaderStyleTag() {
    // when
    baggageMetrics.onBaggageInjected();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    CoreMetric baggageMetric = findMetric(metrics, "context_header_style.injected");
    assertNotNull(baggageMetric);
    assertTrue(baggageMetric.tags.contains("header_style:baggage"));
    assertEquals(1L, baggageMetric.value.longValue());
  }

  @Test
  void shouldCollectBaggageMalformedMetricsWithHeaderStyleTag() {
    // when
    baggageMetrics.onBaggageMalformed();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    CoreMetric baggageMetric = findMetric(metrics, "context_header_style.malformed");
    assertNotNull(baggageMetric);
    assertTrue(baggageMetric.tags.contains("header_style:baggage"));
    assertEquals(1L, baggageMetric.value.longValue());
  }

  @Test
  void shouldCollectBaggageTruncatedMetricsWithByteCountTruncationReasonTag() {
    // when
    baggageMetrics.onBaggageTruncatedByInjectByteLimit();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    CoreMetric baggageMetric =
        findTruncatedMetric(metrics, "truncation_reason:baggage_byte_count_exceeded");
    assertNotNull(baggageMetric);
    assertEquals(1L, baggageMetric.value.longValue());
  }

  @Test
  void shouldCollectBaggageTruncatedMetricsWithItemCountTruncationReasonTag() {
    // when
    baggageMetrics.onBaggageTruncatedByInjectItemLimit();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    CoreMetric baggageMetric =
        findTruncatedMetric(metrics, "truncation_reason:baggage_item_count_exceeded");
    assertNotNull(baggageMetric);
    assertEquals(1L, baggageMetric.value.longValue());
  }

  @Test
  void shouldCollectBaggageTruncatedMetricsWithExtractByteTruncationReasonTag() {
    // when
    baggageMetrics.onBaggageTruncatedByExtractByteLimit();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    CoreMetric baggageMetric =
        findTruncatedMetric(metrics, "truncation_reason:baggage_extract_byte_exceeded");
    assertNotNull(baggageMetric);
    assertEquals(1L, baggageMetric.value.longValue());
  }

  @Test
  void shouldCollectBaggageTruncatedMetricsWithExtractItemTruncationReasonTag() {
    // when
    baggageMetrics.onBaggageTruncatedByExtractItemLimit();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    CoreMetric baggageMetric =
        findTruncatedMetric(metrics, "truncation_reason:baggage_extract_item_exceeded");
    assertNotNull(baggageMetric);
    assertEquals(1L, baggageMetric.value.longValue());
  }

  @Test
  void shouldNotCreateBaggageMetricsWhenNoEventsOccurred() {
    // when
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    assertTrue(metrics.stream().noneMatch(CoreMetricCollectorBaggageTest::isHeaderStyleMetric));
  }

  @Test
  void shouldResetBaggageCountersAfterPrepareMetrics() {
    // when
    baggageMetrics.onBaggageExtracted();
    baggageMetrics.onBaggageInjected();
    collector.prepareMetrics();
    collector.drain();
    collector.prepareMetrics();
    Collection<CoreMetric> metrics = collector.drain();

    // then
    assertTrue(metrics.stream().noneMatch(CoreMetricCollectorBaggageTest::isHeaderStyleMetric));
  }

  private static CoreMetric findMetric(Collection<CoreMetric> metrics, String metricName) {
    return metrics.stream()
        .filter(metric -> metricName.equals(metric.metricName))
        .findFirst()
        .orElse(null);
  }

  private static CoreMetric findTruncatedMetric(Collection<CoreMetric> metrics, String tag) {
    return metrics.stream()
        .filter(
            metric ->
                "context_header.truncated".equals(metric.metricName) && metric.tags.contains(tag))
        .findFirst()
        .orElse(null);
  }

  private static boolean isHeaderStyleMetric(CoreMetric metric) {
    return metric.metricName.startsWith("context_header_style.");
  }
}

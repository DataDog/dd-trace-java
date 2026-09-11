package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LLMObsMetricCollectorTest {
  private final LLMObsMetricCollector collector = LLMObsMetricCollector.get();

  @BeforeEach
  void clearStaleMetrics() {
    collector.resetForTesting();
  }

  @AfterEach
  void clearMetrics() {
    collector.resetForTesting();
  }

  @Test
  void noMetricsDrainsEmptyList() {
    collector.prepareMetrics();

    assertTrue(collector.drain().isEmpty());
  }

  @Test
  void recordsOneMetricPerDistinctTagCombination() {
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.recordSpanFinished("openai", "llm", false, true, false, true);
    collector.recordSpanFinished("anthropic", "embedding", true, false, true, false);
    collector.prepareMetrics();

    List<LLMObsMetricCollector.LLMObsMetric> metrics = sorted(collector.drain());

    assertEquals(3, metrics.size());
    for (LLMObsMetricCollector.LLMObsMetric metric : metrics) {
      assertEquals("mlobs", metric.namespace);
      assertEquals("span.finished", metric.metricName);
      assertEquals("count", metric.type);
      assertEquals(1L, metric.value);
    }
    assertEquals(
        new HashSet<>(
            Arrays.asList(
                new HashSet<>(
                    Arrays.asList(
                        "integration:openai",
                        "span_kind:llm",
                        "is_root_span:1",
                        "autoinstrumented:1",
                        "error:0",
                        "has_session_id:0")),
                new HashSet<>(
                    Arrays.asList(
                        "integration:openai",
                        "span_kind:llm",
                        "is_root_span:0",
                        "autoinstrumented:1",
                        "error:0",
                        "has_session_id:1")),
                new HashSet<>(
                    Arrays.asList(
                        "integration:anthropic",
                        "span_kind:embedding",
                        "is_root_span:1",
                        "autoinstrumented:0",
                        "error:1",
                        "has_session_id:0")))),
        tagSets(metrics));
  }

  @Test
  void aggregatesIdenticalTagCombinationsIntoASingleCount() {
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.prepareMetrics();

    Collection<LLMObsMetricCollector.LLMObsMetric> metrics = collector.drain();

    assertEquals(1, metrics.size());
    LLMObsMetricCollector.LLMObsMetric metric = metrics.iterator().next();
    assertEquals("count", metric.type);
    assertEquals(3L, metric.value);
  }

  /**
   * Regression test for the reported ~100x under-count: emitting one raw metric per span both
   * overflowed the bounded raw queue and collapsed to ~1/s per series at the metrics intake, since
   * points are timestamped at second granularity. The count must survive well past {@link
   * MetricCollector#RAW_QUEUE_SIZE} spans in a single interval.
   */
  @Test
  void reportsExactCountWellBeyondRawQueueSize() {
    int spans = MetricCollector.RAW_QUEUE_SIZE * 10;
    for (int i = 0; i < spans; i++) {
      collector.recordSpanFinished("openai", "llm", false, true, false, false);
    }
    collector.prepareMetrics();

    Collection<LLMObsMetricCollector.LLMObsMetric> metrics = collector.drain();

    assertEquals(1, metrics.size());
    assertEquals((long) spans, metrics.iterator().next().value);
  }

  @Test
  void countersResetBetweenIntervals() {
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.prepareMetrics();
    assertEquals(1, collector.drain().size());

    collector.prepareMetrics();

    assertTrue(collector.drain().isEmpty(), "an idle interval must not re-report a stale count");
  }

  @Test
  void reportsCountsAccumulatedAcrossIntervalsWithoutADrain() {
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.prepareMetrics();
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.recordSpanFinished("openai", "llm", true, true, false, false);
    collector.prepareMetrics();

    List<LLMObsMetricCollector.LLMObsMetric> metrics = sorted(collector.drain());

    assertEquals(2, metrics.size());
    assertEquals(1L, metrics.get(0).value);
    assertEquals(2L, metrics.get(1).value);
  }

  @Test
  void boundsTheNumberOfTrackedTagCombinations() {
    for (int i = 0; i < LLMObsMetricCollector.MAX_TAG_COMBINATIONS * 2; i++) {
      collector.recordSpanFinished("integration-" + i, "llm", true, true, false, false);
    }
    collector.prepareMetrics();

    assertEquals(LLMObsMetricCollector.MAX_TAG_COMBINATIONS, collector.drain().size());
  }

  private static List<LLMObsMetricCollector.LLMObsMetric> sorted(
      Collection<LLMObsMetricCollector.LLMObsMetric> metrics) {
    List<LLMObsMetricCollector.LLMObsMetric> sorted = new ArrayList<>(metrics);
    sorted.sort((a, b) -> Long.compare(a.value.longValue(), b.value.longValue()));
    return sorted;
  }

  private static HashSet<HashSet<String>> tagSets(
      Collection<LLMObsMetricCollector.LLMObsMetric> metrics) {
    HashSet<HashSet<String>> tagSets = new HashSet<>();
    for (LLMObsMetricCollector.LLMObsMetric metric : metrics) {
      tagSets.add(new HashSet<>(metric.tags));
    }
    return tagSets;
  }
}

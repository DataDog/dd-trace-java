package datadog.trace.api.telemetry;

import static datadog.trace.api.telemetry.FlagEvaluationMetricCollector.Counter.DROPPED_CLOSED;
import static datadog.trace.api.telemetry.FlagEvaluationMetricCollector.Counter.PAYLOAD_SPLITS;
import static datadog.trace.api.telemetry.MetricCollector.RAW_QUEUE_SIZE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.FlagEvaluationMetricCollector.Counter;
import datadog.trace.api.telemetry.FlagEvaluationMetricCollector.FlagEvaluationMetric;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class FlagEvaluationMetricCollectorTest {
  private final FlagEvaluationMetricCollector collector = FlagEvaluationMetricCollector.get();

  @BeforeEach
  @AfterEach
  void resetCollector() {
    collector.resetForTesting();
  }

  @Test
  void accumulatesPositiveLongCountsAndDrainsOnlyOnce() {
    collector.count(DROPPED_CLOSED, Integer.MAX_VALUE);
    collector.count(DROPPED_CLOSED, 5);
    collector.count(DROPPED_CLOSED, 0);
    collector.count(DROPPED_CLOSED, -1);

    List<FlagEvaluationMetric> metrics = collect();
    assertEquals(1, metrics.size());
    assertEquals(Integer.MAX_VALUE + 5L, metrics.get(0).value.longValue());
    assertTrue(collect().isEmpty());

    collector.count(DROPPED_CLOSED, 2);
    assertEquals(2, collect().get(0).value.longValue());
  }

  @TableTest({
    "Scenario         | Counter                  | Name                          | Tag                   ",
    "queue overflow   | DROPPED_QUEUE_OVERFLOW   | flagevaluation.rows.dropped   | reason:queue_overflow ",
    "closed           | DROPPED_CLOSED           | flagevaluation.rows.dropped   | reason:closed         ",
    "degraded cap     | DROPPED_DEGRADED_CAP     | flagevaluation.rows.dropped   | reason:degraded_cap   ",
    "payload drop     | DROPPED_PAYLOAD_LIMIT    | flagevaluation.rows.dropped   | reason:payload_limit  ",
    "cardinality cap  | DEGRADED_CARDINALITY_CAP | flagevaluation.rows.degraded  | reason:cardinality_cap",
    "payload degraded | DEGRADED_PAYLOAD_LIMIT   | flagevaluation.rows.degraded  | reason:payload_limit  ",
    "split            | PAYLOAD_SPLITS           | flagevaluation.payload.splits |                       "
  })
  void preservesWireFormat(Counter counter, String name, String tag) {
    collector.count(counter, 3);

    FlagEvaluationMetric metric = collect().get(0);
    assertEquals(name, metric.metricName);
    assertEquals("tracers", metric.namespace);
    assertTrue(metric.common);
    assertEquals("count", metric.type);
    assertEquals(tag == null ? emptyList() : singletonList(tag), metric.tags);
    assertEquals(3L, metric.value.longValue());
  }

  @Test
  void preservesCombinedTruncationReasonsAcrossIntervals() {
    collector.countContextTruncated("max_key_length,max_value_length", 2);
    collector.countContextTruncated("max_key_length,max_value_length", 3);
    collector.countContextTruncated("max_depth", 1);
    collector.countContextTruncated("ignored", 0);
    collector.countContextTruncated("ignored", -1);

    Map<String, Long> counts = valuesByTag(collect());
    assertEquals(2, counts.size());
    assertEquals(5L, counts.get("reason:max_key_length,max_value_length").longValue());
    assertEquals(1L, counts.get("reason:max_depth").longValue());
    assertTrue(collect().isEmpty());

    collector.countContextTruncated("max_depth", 4);
    assertEquals(4L, collect().get(0).value.longValue());
  }

  @Test
  void fullQueueLeavesCountersPendingForTheNextCollection() {
    for (int i = 0; i < RAW_QUEUE_SIZE; i++) {
      collector.count(PAYLOAD_SPLITS, 1);
      collector.prepareMetrics();
    }
    collector.count(DROPPED_CLOSED, 5);
    collector.countContextTruncated("max_depth", 3);
    collector.prepareMetrics();
    collector.count(DROPPED_CLOSED, 11);

    List<FlagEvaluationMetric> staged = new ArrayList<>(collector.drain());
    assertEquals(RAW_QUEUE_SIZE, staged.size());
    assertEquals(RAW_QUEUE_SIZE, sum(staged, "flagevaluation.payload.splits"));

    List<FlagEvaluationMetric> pending = collect();
    assertEquals(16, sum(pending, "flagevaluation.rows.dropped"));
    assertEquals(3, sum(pending, "flagevaluation.context.truncated"));
    assertTrue(collect().isEmpty());
  }

  @Test
  void concurrentProducersAndCollectionPreserveAllCounts() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> producers = new ArrayList<>();
    try {
      for (int producer = 0; producer < 4; producer++) {
        producers.add(
            executor.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < 5000; i++) {
                    collector.count(DROPPED_CLOSED, 1);
                    collector.countContextTruncated("max_depth", 1);
                  }
                  return null;
                }));
      }
      start.countDown();
      Map<String, Long> totals = new HashMap<>();
      for (int i = 0; i < 100; i++) {
        addTo(totals, collect());
      }
      for (Future<?> producer : producers) {
        producer.get(10, TimeUnit.SECONDS);
      }
      addTo(totals, collect());

      assertEquals(20000L, totals.get("flagevaluation.rows.dropped").longValue());
      assertEquals(20000L, totals.get("flagevaluation.context.truncated").longValue());
      assertTrue(collect().isEmpty());
    } finally {
      executor.shutdownNow();
    }
  }

  private List<FlagEvaluationMetric> collect() {
    collector.prepareMetrics();
    return new ArrayList<>(collector.drain());
  }

  private static Map<String, Long> valuesByTag(Collection<FlagEvaluationMetric> metrics) {
    Map<String, Long> values = new HashMap<>();
    for (FlagEvaluationMetric metric : metrics) {
      assertEquals("flagevaluation.context.truncated", metric.metricName);
      values.put(metric.tags.get(0), metric.value.longValue());
    }
    return values;
  }

  private static void addTo(Map<String, Long> totals, Collection<FlagEvaluationMetric> metrics) {
    for (FlagEvaluationMetric metric : metrics) {
      totals.merge(metric.metricName, metric.value.longValue(), Long::sum);
    }
  }

  private static long sum(Collection<FlagEvaluationMetric> metrics, String name) {
    return metrics.stream()
        .filter(metric -> name.equals(metric.metricName))
        .mapToLong(metric -> metric.value.longValue())
        .sum();
  }
}

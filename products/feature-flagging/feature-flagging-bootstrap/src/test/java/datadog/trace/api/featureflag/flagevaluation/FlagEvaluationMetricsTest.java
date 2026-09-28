package datadog.trace.api.featureflag.flagevaluation;

import static datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics.Metric.DROPPED_CLOSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics.Count;
import java.util.ArrayList;
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

class FlagEvaluationMetricsTest {
  private final FlagEvaluationMetrics metrics = FlagEvaluationMetrics.getInstance();

  @BeforeEach
  @AfterEach
  void resetMetrics() {
    metrics.drain();
  }

  @Test
  void accumulatesPositiveLongCountsAndDrainsOnlyOnce() {
    metrics.count(DROPPED_CLOSED, Integer.MAX_VALUE);
    metrics.count(DROPPED_CLOSED, 5);
    metrics.count(DROPPED_CLOSED, 0);
    metrics.count(DROPPED_CLOSED, -1);

    List<Count> counts = metrics.drain();
    assertEquals(1, counts.size());
    assertEquals("flagevaluation.rows.dropped", counts.get(0).name);
    assertEquals("reason:closed", counts.get(0).tag);
    assertEquals(Integer.MAX_VALUE + 5L, counts.get(0).value);
    assertTrue(metrics.drain().isEmpty());

    metrics.count(DROPPED_CLOSED, 2);
    assertEquals(2, metrics.drain().get(0).value);
  }

  @Test
  void preservesCombinedTruncationReasonsAcrossDrains() {
    metrics.countContextTruncated("max_key_length,max_value_length", 2);
    metrics.countContextTruncated("max_key_length,max_value_length", 3);
    metrics.countContextTruncated("max_depth", 1);
    metrics.countContextTruncated("ignored", 0);
    metrics.countContextTruncated("ignored", -1);

    Map<String, Long> counts = new HashMap<>();
    for (Count count : metrics.drain()) {
      assertEquals("flagevaluation.context.truncated", count.name);
      counts.put(count.tag, count.value);
    }
    assertEquals(2, counts.size());
    assertEquals(5L, counts.get("reason:max_key_length,max_value_length").longValue());
    assertEquals(1L, counts.get("reason:max_depth").longValue());
    assertTrue(metrics.drain().isEmpty());

    metrics.countContextTruncated("max_depth", 4);
    assertEquals(4, metrics.drain().get(0).value);
  }

  @Test
  void concurrentProducersAndDrainingPreserveAllCounts() throws Exception {
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
                    metrics.count(DROPPED_CLOSED, 1);
                    metrics.countContextTruncated("max_depth", 1);
                  }
                  return null;
                }));
      }
      start.countDown();
      Map<String, Long> totals = new HashMap<>();
      for (int i = 0; i < 100; i++) {
        addTo(totals, metrics.drain());
      }
      for (Future<?> producer : producers) {
        producer.get(10, TimeUnit.SECONDS);
      }
      addTo(totals, metrics.drain());
      assertEquals(20000L, totals.get("flagevaluation.rows.dropped").longValue());
      assertEquals(20000L, totals.get("flagevaluation.context.truncated").longValue());
      assertTrue(metrics.drain().isEmpty());
    } finally {
      executor.shutdownNow();
    }
  }

  private static void addTo(Map<String, Long> totals, List<Count> counts) {
    for (Count count : counts) {
      totals.merge(count.name, count.value, Long::sum);
    }
  }
}

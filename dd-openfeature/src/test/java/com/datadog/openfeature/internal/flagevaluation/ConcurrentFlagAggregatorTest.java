package com.datadog.openfeature.internal.flagevaluation;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrentFlagAggregatorTest {

  /** The same evaluations give the same rows as the worker-thread aggregator. */
  @Test
  void producesTheSameRowsAsTheWorkerAggregator() {
    final Random random = new Random(11);
    final FlagEvaluationAggregator reference = new FlagEvaluationAggregator();
    final ConcurrentFlagAggregator concurrent = new ConcurrentFlagAggregator();
    for (int i = 0; i < 20_000; i++) {
      final String flag = "flag-" + random.nextInt(20);
      final String variant = random.nextInt(10) == 0 ? null : "v" + random.nextInt(3);
      final String allocation = "alloc-" + random.nextInt(2);
      final String subject = "user-" + random.nextInt(300);
      final String error = random.nextInt(20) == 0 ? "PARSE_ERROR" : null;
      final long time = 1_000L + random.nextInt(10_000);
      final boolean consent = random.nextBoolean();
      final Map<String, Object> attrs =
          consent ? singletonMap("plan", (Object) ("p" + random.nextInt(3))) : emptyMap();
      final Map<String, Object> features =
          random.nextInt(4) == 0 ? singletonMap("f", (Object) "x") : emptyMap();
      reference.aggregate(
          new FlagEvalEvent(
              flag, variant, allocation, subject, error, time, consent, attrs, features));
      concurrent.record(flag, variant, allocation, subject, error, time, consent, attrs, features);
    }

    final ConcurrentFlagAggregator.Window window = concurrent.swap();
    assertEquals(rows(reference), rows(window));
  }

  @Test
  void countsEveryEvaluationFromManyThreads() throws Exception {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    final int threads = 8;
    final int perThread = 50_000;
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      final List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        final int thread = t;
        futures.add(
            executor.submit(() -> {
              start.await();
              for (int i = 0; i < perThread; i++) {
                // Few keys, so threads contend on the same buckets and race on insertion.
                aggregator.record(
                    "flag-" + (i % 4),
                    "on",
                    "alloc",
                    "user-" + (i % 16),
                    null,
                    1_000L + thread + i,
                    false,
                    emptyMap(),
                    emptyMap());
              }
              return null;
            }));
      }
      start.countDown();
      for (final Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    final ConcurrentFlagAggregator.Window window = aggregator.swap();
    final long[] total = {0};
    final int[] buckets = {0};
    window.forEachFull(bucket -> {
      total[0] += bucket.count;
      buckets[0]++;
    });
    assertEquals((long) threads * perThread, total[0]);
    // i % 16 determines i % 4, so there are 16 distinct (flag, subject) keys, each inserted once.
    assertEquals(16, buckets[0]);
  }

  @Test
  void spillsIntoTheDegradedTierAtTheFlagCapAndKeepsMixedConsentOff() {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    for (int i = 0; i < FlagEvaluationAggregator.PER_FLAG_CAP; i++) {
      aggregator.record("hot", "on", "alloc", "user-" + i, null, 1L, true, emptyMap(), emptyMap());
    }
    // Over the cap: two new subjects, one with consent and one without, share a degraded row.
    aggregator.record("hot", "on", "alloc", "late-1", null, 5L, true, emptyMap(), emptyMap());
    aggregator.record("hot", "on", "alloc", "late-2", null, 9L, false, emptyMap(), emptyMap());

    final ConcurrentFlagAggregator.Window window = aggregator.swap();
    final List<ConcurrentFlagAggregator.Bucket> degraded = new ArrayList<>();
    window.forEachDegraded(degraded::add);
    assertEquals(1, degraded.size());
    final ConcurrentFlagAggregator.Bucket row = degraded.get(0);
    assertEquals(2, row.count);
    assertEquals(5L, row.firstEvalMs);
    assertEquals(9L, row.lastEvalMs);
    assertFalse(row.observeFullEvaluationData);
    assertEquals(2, window.degradedEvaluationCount());
  }

  @Test
  void swapsInAFreshWindowAndRecyclesTheOldOne() {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    assertTrue(aggregator.isEmpty());
    aggregator.record("f", "on", "a", "u", null, 1L, false, emptyMap(), emptyMap());
    assertFalse(aggregator.isEmpty());

    final ConcurrentFlagAggregator.Window first = aggregator.swap();
    assertTrue(aggregator.isEmpty());
    assertEquals(1, first.bucketCount());
    aggregator.recycle(first);
    assertTrue(first.isEmpty());

    aggregator.record("f", "on", "a", "u", null, 2L, false, emptyMap(), emptyMap());
    final ConcurrentFlagAggregator.Window second = aggregator.swap();
    assertNotSame(first, second);
    aggregator.recycle(second);
    assertSame(first, aggregator.swap()); // the recycled window comes back
  }

  /** A row's key and its aggregated values, for comparing the two aggregators. */
  private static Map<String, String> rows(final FlagEvaluationAggregator aggregator) {
    final Map<String, String> rows = new HashMap<>();
    for (final FlagEvaluationAggregator.EvalBucket b : aggregator.fullBuckets()) {
      rows.put(
          "full|"
              + key(b.flagKey, b.variant, b.allocationKey, b.targetingKey, b.errorMessage)
              + "|"
              + b.contextKey
              + "|"
              + b.observeFullEvaluationData
              + "|"
              + b.featuresKey,
          b.count + "/" + b.firstEvalMs + "/" + b.lastEvalMs + "/" + b.runtimeDefaultUsed);
    }
    for (final FlagEvaluationAggregator.EvalBucket b : aggregator.degradedBuckets()) {
      rows.put(
          "degraded|"
              + key(b.flagKey, b.variant, b.allocationKey, null, b.errorMessage)
              + "|"
              + b.featuresKey,
          b.count + "/" + b.firstEvalMs + "/" + b.lastEvalMs + "/" + b.runtimeDefaultUsed);
    }
    return rows;
  }

  private static Map<String, String> rows(final ConcurrentFlagAggregator.Window window) {
    final Map<String, String> rows = new HashMap<>();
    window.forEachFull(b ->
        rows.put(
            "full|"
                + key(b.flagKey, b.variant, b.allocationKey, b.targetingKey, b.errorMessage)
                + "|"
                + b.contextKey
                + "|"
                + b.observeFullEvaluationData
                + "|"
                + b.featuresKey,
            b.count + "/" + b.firstEvalMs + "/" + b.lastEvalMs + "/" + b.runtimeDefaultUsed));
    window.forEachDegraded(b ->
        rows.put(
            "degraded|"
                + key(b.flagKey, b.variant, b.allocationKey, null, b.errorMessage)
                + "|"
                + b.featuresKey,
            b.count + "/" + b.firstEvalMs + "/" + b.lastEvalMs + "/" + b.runtimeDefaultUsed));
    return rows;
  }

  private static String key(
      final String flag,
      final String variant,
      final String allocation,
      final String subject,
      final String error) {
    return flag + "|" + variant + "|" + allocation + "|" + subject + "|" + error;
  }
}

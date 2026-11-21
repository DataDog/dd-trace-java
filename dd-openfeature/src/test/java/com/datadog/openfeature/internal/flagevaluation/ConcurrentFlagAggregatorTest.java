package com.datadog.openfeature.internal.flagevaluation;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
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

    assertEquals(rows(reference), rows(concurrent));
  }

  @Test
  void countsEveryEvaluationFromManyThreads() throws Exception {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    final int threads = 8;
    final int perThread = 50_000;
    runProducers(threads, perThread, aggregator, () -> {});

    final long[] total = {0};
    final int rows =
        aggregator.drain((bucket, count, first, last, defaultUsed) -> total[0] += count);
    assertEquals((long) threads * perThread, total[0]);
    // i % 16 determines i % 4, so there are 16 distinct (flag, subject) keys, each inserted once.
    assertEquals(16, rows);
    assertEquals(16, aggregator.bucketCount());
  }

  @Test
  void losesNoEvaluationWhileDrainingConcurrently() throws Exception {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    final int threads = 4;
    final int perThread = 100_000;
    final long[] total = {0};
    final ConcurrentFlagAggregator.Rows sum =
        (bucket, count, first, last, defaultUsed) -> total[0] += count;
    // Drain and sweep over and over while the producers run, then once more after.
    runProducers(
        threads,
        perThread,
        aggregator,
        () -> {
          aggregator.drain(sum);
          aggregator.sweepIdle();
        });
    aggregator.drain(sum);

    assertEquals((long) threads * perThread, total[0]);
    assertTrue(aggregator.isEmpty());
  }

  @Test
  void drainsOnlyTouchedBucketsAndResetsThem() {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    assertTrue(aggregator.isEmpty());
    record(aggregator, "a", 1L);
    record(aggregator, "a", 3L);
    record(aggregator, "b", 2L);
    assertFalse(aggregator.isEmpty());

    final List<String> first = new ArrayList<>();
    assertEquals(2, aggregator.drain(collect(first)));
    assertEquals(asList("a 2 1-3", "b 1 2-2"), sorted(first));
    assertTrue(aggregator.isEmpty());

    record(aggregator, "a", 7L);
    final List<String> second = new ArrayList<>();
    assertEquals(1, aggregator.drain(collect(second)));
    assertEquals(singletonList("a 1 7-7"), second);
    assertEquals(2, aggregator.bucketCount(), "drained buckets stay for the next window");
  }

  @Test
  void sweepsBucketsIdleForAWholeWindowAndFreesTheirFlagSlots() {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    record(aggregator, "busy", 1L);
    record(aggregator, "idle", 1L);
    aggregator.drain(collect(new ArrayList<>()));
    assertEquals(0, aggregator.sweepIdle(), "both were touched in the window just drained");

    record(aggregator, "busy", 2L);
    aggregator.drain(collect(new ArrayList<>()));
    assertEquals(1, aggregator.sweepIdle());
    assertEquals(1, aggregator.bucketCount());

    // The idle bucket's flag slot was given back, so its flag can fill to the cap again.
    for (int i = 0; i < FlagEvaluationAggregator.PER_FLAG_CAP; i++) {
      aggregator.record("idle", "on", "alloc", "u" + i, null, 3L, false, emptyMap(), emptyMap());
    }
    final long[] degraded = {0};
    aggregator.drain((bucket, count, first, last, defaultUsed) -> {
      if (bucket.degraded) {
        degraded[0] += count;
      }
    });
    assertEquals(0, degraded[0]);
  }

  @Test
  void aBucketCreatedAfterADrainIsNotSweptBeforeItsWindowEnds() {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    aggregator.drain(collect(new ArrayList<>()));
    record(aggregator, "new", 1L);
    assertEquals(0, aggregator.sweepIdle());
    assertEquals(1, aggregator.drain(collect(new ArrayList<>())));
  }

  @Test
  void spillsIntoTheDegradedTierAtTheFlagCapAndKeepsMixedConsentOff() {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    for (int i = 0; i < FlagEvaluationAggregator.PER_FLAG_CAP; i++) {
      aggregator.record("hot", "on", "alloc", "user-" + i, null, 1L, true, emptyMap(), emptyMap());
    }
    // Over the cap: two new subjects, one with consent and one without, share a degraded row.
    aggregator.record("hot", "on", "alloc", "late-1", null, 5L, true, emptyMap(), emptyMap());
    aggregator.record("hot", null, "alloc", "late-2", null, 9L, false, emptyMap(), emptyMap());
    aggregator.record("hot", null, "alloc", "late-3", null, 7L, false, emptyMap(), emptyMap());

    final List<String> degraded = new ArrayList<>();
    aggregator.drain((bucket, count, first, last, defaultUsed) -> {
      if (bucket.degraded) {
        degraded.add(
            bucket.variant
                + " "
                + count
                + " "
                + first
                + "-"
                + last
                + " "
                + defaultUsed
                + " "
                + bucket.observeFullEvaluationData);
      }
    });
    assertEquals(asList("null 2 7-9 true false", "on 1 5-5 false true"), sorted(degraded));
  }

  @Test
  void dropsEvaluationsBeyondTheDegradedCap() {
    final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
    for (int i = 0; i < FlagEvaluationAggregator.GLOBAL_CAP; i++) {
      aggregator.record(
          "f" + (i % 20), "on", "a", "u" + i, null, 1L, false, emptyMap(), emptyMap());
    }
    for (int i = 0; i < FlagEvaluationAggregator.DEGRADED_CAP + 5; i++) {
      aggregator.record("g" + i, "on", "a", "u", null, 1L, false, emptyMap(), emptyMap());
    }
    assertEquals(5, aggregator.droppedDegradedOverflow.get());
  }

  @Test
  void theCallerSidePipelinePostsTheDrainedRows() {
    final FlagEvaluationTestSupport.CapturingTransport transport =
        new FlagEvaluationTestSupport.CapturingTransport();
    final FlagEvaluationPipeline.SerializingHandlerForTest handler =
        new FlagEvaluationPipeline.SerializingHandlerForTest(
            transport,
            singletonMap("service", "svc"),
            FlagEvaluationPipeline.FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES,
            new RuntimeServices(HealthMetrics.NOOP),
            true);
    handler.concurrent.record("f", "on", "a", "u", null, 5L, false, emptyMap(), emptyMap());
    handler.concurrent.record("f", "on", "a", "u", null, 6L, false, emptyMap(), emptyMap());
    handler.flush();
    handler.flush(); // nothing new: posts nothing

    assertEquals(1, transport.posts.size());
    final String body = new String(transport.posts.get(0).body, StandardCharsets.UTF_8);
    assertTrue(body.contains("\"evaluation_count\":2"), body);
  }

  @Test
  void bucketsMatchOnEveryKeyDimension() {
    final Map<String, Object> ctx = singletonMap("k", (Object) "v");
    final Map<String, Object> feats = singletonMap("f", (Object) 1);
    final ConcurrentFlagAggregator.Bucket full =
        bucket(false, "f", "on", "a", "u", "e", ctx, true, feats);
    assertTrue(full.matches(bucket(false, "f", "on", "a", "u", "e", ctx, true, feats)));
    assertFalse(full.matches(bucket(false, "x", "on", "a", "u", "e", ctx, true, feats)));
    assertFalse(full.matches(bucket(false, "f", "off", "a", "u", "e", ctx, true, feats)));
    assertFalse(full.matches(bucket(false, "f", "on", "b", "u", "e", ctx, true, feats)));
    assertFalse(full.matches(bucket(false, "f", "on", "a", "w", "e", ctx, true, feats)));
    assertFalse(full.matches(bucket(false, "f", "on", "a", "u", "x", ctx, true, feats)));
    assertFalse(full.matches(bucket(false, "f", "on", "a", "u", "e", emptyMap(), true, feats)));
    assertFalse(full.matches(bucket(false, "f", "on", "a", "u", "e", ctx, false, feats)));
    assertFalse(full.matches(bucket(false, "f", "on", "a", "u", "e", ctx, true, emptyMap())));

    final ConcurrentFlagAggregator.Bucket degraded =
        bucket(true, "f", "on", "a", null, "e", null, false, feats);
    assertTrue(degraded.matches(bucket(true, "f", "on", "a", "other", "e", ctx, true, feats)));
    assertFalse(degraded.matches(bucket(true, "x", "on", "a", null, "e", null, false, feats)));
    assertFalse(degraded.matches(bucket(true, "f", "off", "a", null, "e", null, false, feats)));
    assertFalse(degraded.matches(bucket(true, "f", "on", "b", null, "e", null, false, feats)));
    assertFalse(degraded.matches(bucket(true, "f", "on", "a", null, "x", null, false, feats)));
    assertFalse(degraded.matches(bucket(true, "f", "on", "a", null, "e", null, false, emptyMap())));
  }

  @Test
  void rowsWhoseTimestampsRacedTheDrainReadAsTheFlushTime() {
    final ConcurrentFlagAggregator.Bucket b =
        bucket(false, "f", "on", "a", "u", null, null, false, emptyMap());

    final FlagEvaluationPayloads.FlagEvaluationEvent untimed =
        FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(
            b, 1, Long.MAX_VALUE, Long.MIN_VALUE, false, 42L);
    final FlagEvaluationPayloads.FlagEvaluationEvent timed =
        FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(b, 1, 5L, 9L, true, 42L);

    assertEquals(42L, untimed.first_evaluation);
    assertEquals(42L, untimed.last_evaluation);
    assertEquals(5L, timed.first_evaluation);
    assertEquals(9L, timed.last_evaluation);
  }

  private static ConcurrentFlagAggregator.Bucket bucket(
      final boolean degraded,
      final String flag,
      final String variant,
      final String allocation,
      final String subject,
      final String error,
      final Map<String, Object> context,
      final boolean consent,
      final Map<String, Object> features) {
    return new ConcurrentFlagAggregator.Bucket(
        degraded,
        1L,
        flag,
        variant,
        allocation,
        subject,
        error,
        context,
        consent,
        features,
        null,
        0);
  }

  private static void record(
      final ConcurrentFlagAggregator aggregator, final String flag, final long time) {
    aggregator.record(flag, "on", "alloc", "user", null, time, false, emptyMap(), emptyMap());
  }

  private static ConcurrentFlagAggregator.Rows collect(final List<String> rows) {
    return (bucket, count, first, last, defaultUsed) ->
        rows.add(bucket.flagKey + " " + count + " " + first + "-" + last);
  }

  private static List<String> sorted(final List<String> rows) {
    final List<String> copy = new ArrayList<>(rows);
    Collections.sort(copy);
    return copy;
  }

  /** Runs producers on few keys, calling {@code meanwhile} repeatedly until they finish. */
  private static void runProducers(
      final int threads,
      final int perThread,
      final ConcurrentFlagAggregator aggregator,
      final Runnable meanwhile)
      throws Exception {
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
        while (!future.isDone()) {
          meanwhile.run();
        }
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /** A row's key and its aggregated values, for comparing the two aggregators. */
  private static Map<String, String> rows(final FlagEvaluationAggregator aggregator) {
    final Map<String, String> rows = new HashMap<>();
    for (final FlagEvaluationAggregator.EvalBucket b : aggregator.fullBuckets()) {
      rows.put(
          fullKey(b.flagKey, b.variant, b.allocationKey, b.targetingKey, b.errorMessage)
              + b.observeFullEvaluationData
              + "|"
              + sortedMap(b.prunedAttrs)
              + "|"
              + sortedMap(b.features),
          b.count + "/" + b.firstEvalMs + "/" + b.lastEvalMs + "/" + b.runtimeDefaultUsed);
    }
    for (final FlagEvaluationAggregator.EvalBucket b : aggregator.degradedBuckets()) {
      rows.put(
          degradedKey(b.flagKey, b.variant, b.allocationKey, b.errorMessage)
              + sortedMap(b.features),
          b.count + "/" + b.firstEvalMs + "/" + b.lastEvalMs + "/" + b.runtimeDefaultUsed);
    }
    return rows;
  }

  private static Map<String, String> rows(final ConcurrentFlagAggregator aggregator) {
    final Map<String, String> rows = new HashMap<>();
    aggregator.drain((b, count, first, last, defaultUsed) ->
        rows.put(
            b.degraded
                ? degradedKey(b.flagKey, b.variant, b.allocationKey, b.errorMessage)
                    + sortedMap(b.features)
                : fullKey(b.flagKey, b.variant, b.allocationKey, b.targetingKey, b.errorMessage)
                    + b.observeFullEvaluationData
                    + "|"
                    + sortedMap(b.prunedAttrs)
                    + "|"
                    + sortedMap(b.features),
            count + "/" + first + "/" + last + "/" + defaultUsed));
    return rows;
  }

  private static String fullKey(
      final String flag,
      final String variant,
      final String allocation,
      final String subject,
      final String error) {
    return "full|" + flag + "|" + variant + "|" + allocation + "|" + subject + "|" + error + "|";
  }

  private static String degradedKey(
      final String flag, final String variant, final String allocation, final String error) {
    return "degraded|" + flag + "|" + variant + "|" + allocation + "|" + error + "|";
  }

  private static String sortedMap(final Map<String, Object> map) {
    return map == null || map.isEmpty() ? "{}" : new TreeMap<>(map).toString();
  }
}

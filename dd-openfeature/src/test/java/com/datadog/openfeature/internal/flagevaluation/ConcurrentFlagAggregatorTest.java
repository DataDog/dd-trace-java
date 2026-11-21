package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.flagevaluation.ConcurrentFlagAggregator.DEGRADED_CAP;
import static com.datadog.openfeature.internal.flagevaluation.ConcurrentFlagAggregator.FLAG_COUNT_CAP;
import static com.datadog.openfeature.internal.flagevaluation.ConcurrentFlagAggregator.GLOBAL_CAP;
import static com.datadog.openfeature.internal.flagevaluation.ConcurrentFlagAggregator.PER_FLAG_CAP;
import static com.datadog.openfeature.internal.flagevaluation.ConcurrentFlagAggregator.contextHash;
import static com.datadog.openfeature.internal.flagevaluation.ConcurrentFlagAggregator.sameContext;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrentFlagAggregatorTest {

  private final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();

  // ---- Aggregation and bucket keys ----

  @Test
  void identicalEvaluationsAggregateIntoOneRow() {
    record("flag-a", "on", "user-1", 2000L);
    record("flag-a", "on", "user-1", 1000L);

    final List<Row> rows = drain();
    assertEquals(1, rows.size());
    assertEquals(2, rows.get(0).count);
    assertEquals(1000L, rows.get(0).first);
    assertEquals(2000L, rows.get(0).last);
    assertFalse(rows.get(0).defaultUsed);
  }

  @Test
  void absentVariantSetsRuntimeDefaultUsed() {
    record("flag-c", null, "user-1", 1000L);

    assertTrue(drain().get(0).defaultUsed);
  }

  @Test
  void contextValuesOfDifferentTypesSplitBuckets() {
    full("flag-b", "user-1", singletonMap("score", 1));
    full("flag-b", "user-1", singletonMap("score", "1"));
    full("flag-b", "user-1", singletonMap("score", 1L));
    full("flag-b", "user-1", singletonMap("score", 1));

    assertEquals(3, drain().size());
  }

  @Test
  void nulCharactersInKeyFieldsDoNotCollide() {
    record("a\0b", "c", "user-1", 1000L);
    record("a", "b\0c", "user-1", 1000L);

    assertEquals(2, drain().size());
  }

  @Test
  void storesThePrunedContextVerbatim() {
    // DDEvaluator#copyPrunedContext prunes on the evaluating thread; the aggregator keeps it as is.
    final Map<String, Object> attrs = new HashMap<>();
    for (int i = 0; i < 100; i++) {
      attrs.put("key" + i, "v" + i);
    }
    full("flag-d", "user-1", attrs);

    assertSame(attrs, drain().get(0).bucket.prunedAttrs);
  }

  @Test
  void fullTierSplitsBucketsOnEveryDimension() {
    final Map<String, Object> context = singletonMap("plan", "pro");
    full("flag", "on", "alloc", "error", "user", context);
    full("flag", "on", "alloc", "error", "user", new HashMap<>(context));
    full("other", "on", "alloc", "error", "user", context);
    full("flag", "off", "alloc", "error", "user", context);
    full("flag", null, "alloc", "error", "user", context);
    full("flag", "on", "other", "error", "user", context);
    full("flag", "on", "alloc", "other", "user", context);
    full("flag", "on", "alloc", "error", "other", context);
    full("flag", "on", "alloc", "error", "user", singletonMap("plan", "free"));

    final List<Row> rows = drain();
    assertEquals(8, rows.size());
    assertEquals(1, rows.stream().filter(r -> r.count == 2).count());
  }

  @Test
  void mixedConsentEvaluationsOfOneSubjectLandInDistinctBuckets() {
    // Consent is a full-tier key dimension: the two emit different rows (raw vs hashed targeting
    // key, context vs none), and merging them would downgrade the consent-on row.
    aggregator.record("fold-flag", "on", "a", "user-1", null, 1L, true, emptyMap(), emptyMap());
    aggregator.record("fold-flag", "on", "a", "user-1", null, 2L, false, emptyMap(), emptyMap());
    aggregator.record("fold-flag", "on", "a", "user-1", null, 3L, true, emptyMap(), emptyMap());

    final List<Row> rows = drain();
    assertEquals(2, rows.size());
    for (final Row row : rows) {
      assertEquals(row.bucket.observeFullEvaluationData ? 2 : 1, row.count);
    }
  }

  @Test
  void withoutConsentDifferingContextsCollapseIntoOneBucket() {
    // The context is dropped on emit, so it must not fragment buckets, or the per-flag cap blows
    // out under real traffic; nor is it stored.
    for (int i = 0; i < 3; i++) {
      aggregator.record(
          "checkout",
          "on",
          "a",
          "alice",
          null,
          1L,
          false,
          singletonMap("request_id", "req-" + i),
          emptyMap());
    }
    aggregator.record("checkout", "on", "a", "bob", null, 1L, false, emptyMap(), emptyMap());

    final List<Row> rows = drain();
    assertEquals(2, rows.size(), "subjects still split");
    final Row alice =
        rows.stream().filter(r -> "alice".equals(r.bucket.targetingKey)).findFirst().get();
    assertEquals(3, alice.count);
    assertNull(alice.bucket.prunedAttrs);
  }

  @Test
  void withConsentDifferingContextsSplitBuckets() {
    full("checkout", "alice", singletonMap("request_id", "req-1"));
    full("checkout", "alice", singletonMap("request_id", "req-2"));

    assertEquals(2, drain().size());
  }

  @Test
  void featuresSplitBucketsInBothTiersWhateverTheConsent() {
    final Map<String, Object> p1 = singletonMap("bandit.policy_id", "p1");
    final Map<String, Object> p2 = singletonMap("bandit.policy_id", "p2");
    // Null and empty features are the same.
    aggregator.record("feature-flag", "on", "a", "user-1", null, 1L, false, emptyMap(), null);
    aggregator.record("feature-flag", "on", "a", "user-1", null, 1L, false, emptyMap(), emptyMap());
    aggregator.record("feature-flag", "on", "a", "user-1", null, 1L, false, emptyMap(), p1);
    aggregator.record("feature-flag", "on", "a", "user-1", null, 1L, false, emptyMap(), p2);
    aggregator.record("feature-flag", "on", "a", "user-1", null, 1L, false, emptyMap(), p1);
    assertEquals(asList("{bandit.policy_id=p1}=2", "{bandit.policy_id=p2}=1", "{}=2"), features());

    fillFullTier();
    aggregator.record("feature-flag", "on", "a", "user-2", null, 1L, true, emptyMap(), p1);
    aggregator.record("feature-flag", "on", "a", "user-3", null, 1L, false, emptyMap(), p2);
    aggregator.record("feature-flag", "on", "a", "user-4", null, 1L, false, emptyMap(), p1);
    final List<Row> degraded =
        drain().stream()
            .filter(r -> r.bucket.degraded && "feature-flag".equals(r.bucket.flagKey))
            .collect(toList());
    assertEquals(2, degraded.size());
    for (final Row row : degraded) {
      assertEquals(p1.equals(row.bucket.features) ? 2 : 1, row.count);
    }
  }

  // ---- Tiers and caps ----

  @Test
  void spillsIntoTheDegradedTierAtTheFlagCapAndKeepsMixedConsentOff() {
    for (int i = 0; i < PER_FLAG_CAP; i++) {
      aggregator.record("hot", "on", "alloc", "user-" + i, null, 1L, true, emptyMap(), emptyMap());
    }
    // Over the cap: new subjects, with and without consent, share degraded rows.
    aggregator.record("hot", "on", "alloc", "late-1", null, 5L, true, emptyMap(), emptyMap());
    aggregator.record("hot", null, "alloc", "late-2", null, 9L, false, emptyMap(), emptyMap());
    aggregator.record("hot", null, "alloc", "late-3", null, 7L, true, emptyMap(), emptyMap());
    // Another flag still has room in the full tier.
    record("cool", "on", "user-1", 1L);

    final List<String> degraded = new ArrayList<>();
    long full = 0;
    for (final Row row : drain()) {
      if (row.bucket.degraded) {
        assertNull(row.bucket.targetingKey);
        degraded.add(
            row.bucket.variant
                + " "
                + row.count
                + " "
                + row.first
                + "-"
                + row.last
                + " "
                + row.defaultUsed
                + " "
                + row.bucket.observeFullEvaluationData);
      } else {
        full += row.count;
      }
    }
    Collections.sort(degraded);
    assertEquals(asList("null 2 7-9 true false", "on 1 5-5 false true"), degraded);
    assertEquals(PER_FLAG_CAP + 1, full);
  }

  @Test
  void spillsIntoTheDegradedTierAtTheGlobalCap() {
    fillFullTier();
    full("flag", "on", "alloc", "error", "user-1", emptyMap());
    full("flag", "on", "alloc", "error", "user-2", singletonMap("k", "v"));
    full("other", "on", "alloc", "error", "user", emptyMap());
    full("flag", "off", "alloc", "error", "user", emptyMap());
    full("flag", null, "alloc", "error", "user", emptyMap());
    full("flag", "on", "other", "error", "user", emptyMap());
    full("flag", "on", "alloc", "other", "user", emptyMap());

    final List<Row> degraded = drain().stream().filter(r -> r.bucket.degraded).collect(toList());
    assertEquals(6, degraded.size(), "the degraded tier splits on all but subject and context");
    assertEquals(0, aggregator.droppedDegradedOverflow.get());
  }

  @Test
  void dropsAndCountsEvaluationsBeyondTheDegradedCap() {
    fillFullTier();
    for (int i = 0; i < DEGRADED_CAP + 5; i++) {
      aggregator.record("g" + i, "on", "a", "u", null, 1L, false, emptyMap(), emptyMap());
    }
    // An existing degraded bucket still counts at the cap.
    aggregator.record("g0", "on", "a", "u", null, 1L, false, emptyMap(), emptyMap());

    assertEquals(5, aggregator.droppedDegradedOverflow.get());
  }

  @Test
  void flagsBeyondTheFlagCountCapGoToTheDegradedTier() {
    for (int i = 0; i < FLAG_COUNT_CAP; i++) {
      record("flag-" + i, "on", "user", 1L);
    }
    record("one-too-many", "on", "user", 1L);

    final List<Row> degraded = drain().stream().filter(r -> r.bucket.degraded).collect(toList());
    assertEquals(1, degraded.size());
    assertEquals("one-too-many", degraded.get(0).bucket.flagKey);
  }

  @Test
  void capSizingUsesNamedScaleConstants() {
    assertEquals(125_000, ConcurrentFlagAggregator.FULL_BUCKET_SIZING_BASIS);
    assertEquals(10_000, ConcurrentFlagAggregator.PER_FLAG_BUCKET_SIZING_BASIS);
    assertEquals(25_000, ConcurrentFlagAggregator.DEGRADED_BUCKET_SIZING_BASIS);
    assertEquals(131_072, GLOBAL_CAP);
    assertEquals(10_000, PER_FLAG_CAP);
    assertEquals(32_768, DEGRADED_CAP);
  }

  @Test
  void bucketsMatchOnEveryKeyDimension() {
    final Map<String, Object> ctx = singletonMap("k", "v");
    final Map<String, Object> feats = singletonMap("f", 1);
    final ConcurrentFlagAggregator.Bucket full =
        bucket(false, "f", "on", "a", "u", "e", ctx, true, feats);
    assertTrue(
        full.matches(bucket(false, "f", "on", "a", "u", "e", new HashMap<>(ctx), true, feats)));
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

  // ---- Context hashing ----

  @Test
  void emptyAndMissingContextsAreTheSame() {
    assertEquals(0L, contextHash(null));
    assertEquals(0L, contextHash(emptyMap()));
    assertTrue(sameContext(null, emptyMap()));
    assertTrue(sameContext(emptyMap(), null));
    assertFalse(sameContext(null, singletonMap("k", "v")));
  }

  @Test
  void contextHashIgnoresOrderButNotValueTypes() {
    final Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("bool", true);
    attrs.put("double", 1.5d);
    attrs.put("float", 1.25f);
    attrs.put("int", 1);
    attrs.put("long", 2L);
    attrs.put("null", null);
    attrs.put("string", "value");
    final Map<String, Object> reversed = new LinkedHashMap<>();
    final List<String> keys = new ArrayList<>(attrs.keySet());
    Collections.reverse(keys);
    for (final String key : keys) {
      reversed.put(key, attrs.get(key));
    }

    assertEquals(contextHash(attrs), contextHash(reversed));
    assertTrue(sameContext(attrs, reversed));

    final Map<String, Object> intAsLong = new HashMap<>(attrs);
    intAsLong.put("int", 1L);
    assertNotEquals(contextHash(attrs), contextHash(intAsLong));
    assertFalse(sameContext(attrs, intAsLong));
  }

  @Test
  void contextHashTellsSwappedValuesApart() {
    final Map<String, Object> ab = new HashMap<>();
    ab.put("a", 1);
    ab.put("b", 2);
    final Map<String, Object> ba = new HashMap<>();
    ba.put("a", 2);
    ba.put("b", 1);

    assertNotEquals(contextHash(ab), contextHash(ba));
  }

  // ---- Drain in place and idle sweep ----

  @Test
  void drainsOnlyTouchedBucketsAndResetsThem() {
    assertTrue(aggregator.isEmpty());
    record("a", "on", "user", 1L);
    record("a", "on", "user", 3L);
    record("b", "on", "user", 2L);
    assertFalse(aggregator.isEmpty());

    assertEquals(asList("a 2 1-3", "b 1 2-2"), summaries(drain()));
    assertTrue(aggregator.isEmpty());

    record("a", "on", "user", 7L);
    assertEquals(singletonList("a 1 7-7"), summaries(drain()));
    assertEquals(2, aggregator.bucketCount(), "drained buckets stay for the next window");
  }

  @Test
  void aRowThatThrowsDoesNotStrandTheOtherBuckets() {
    record("a", "on", "user", 1L);
    record("b", "on", "user", 1L);
    final List<String> seen = new ArrayList<>();
    final IllegalStateException failure = new IllegalStateException("boom");

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                aggregator.drain((bucket, count, first, last, defaultUsed) -> {
                  seen.add(bucket.flagKey);
                  throw failure;
                })));

    assertEquals(2, seen.size(), "every bucket is still drained");
    assertTrue(aggregator.isEmpty());
    record("a", "on", "user", 2L);
    assertEquals(singletonList("a 1 2-2"), summaries(drain()), "and pushed again when touched");
  }

  @Test
  void sweepsBucketsIdleForAWholeWindowAndFreesTheirFlagSlots() {
    record("busy", "on", "user", 1L);
    for (int i = 0; i < PER_FLAG_CAP; i++) {
      aggregator.record("idle", "on", "alloc", "u" + i, null, 1L, false, emptyMap(), emptyMap());
    }
    record("idle", "on", "spilled", 1L);
    drain();
    assertEquals(0, aggregator.sweepIdle(), "all were touched in the window just drained");

    record("busy", "on", "user", 2L);
    drain();
    assertEquals(PER_FLAG_CAP + 1, aggregator.sweepIdle());
    assertEquals(1, aggregator.bucketCount());

    // The idle buckets' flag slots were given back, so the flag fills the full tier again.
    for (int i = 0; i < PER_FLAG_CAP; i++) {
      aggregator.record("idle", "on", "alloc", "v" + i, null, 3L, false, emptyMap(), emptyMap());
    }
    assertEquals(0, drain().stream().filter(r -> r.bucket.degraded).count());
  }

  @Test
  void aBucketCreatedAfterADrainIsNotSweptBeforeItsWindowEnds() {
    drain();
    record("new", "on", "user", 1L);
    assertEquals(0, aggregator.sweepIdle());
    assertEquals(1, drain().size());
  }

  // ---- Concurrency ----

  @Test
  void countsEveryEvaluationFromManyThreads() throws Exception {
    final int threads = 8;
    final int perThread = 50_000;
    runProducers(threads, perThread, () -> {});

    final List<Row> rows = drain();
    assertEquals((long) threads * perThread, rows.stream().mapToLong(r -> r.count).sum());
    // i % 16 determines i % 4, so there are 16 distinct (flag, subject) keys, each inserted once.
    assertEquals(16, rows.size());
    assertEquals(16, aggregator.bucketCount());
  }

  @Test
  void losesNoEvaluationWhileDrainingConcurrently() throws Exception {
    final int threads = 4;
    final int perThread = 100_000;
    final long[] total = {0};
    final ConcurrentFlagAggregator.Rows sum =
        (bucket, count, first, last, defaultUsed) -> total[0] += count;
    // Drain and sweep over and over while the producers run, then once more after.
    runProducers(
        threads,
        perThread,
        () -> {
          aggregator.drain(sum);
          aggregator.sweepIdle();
        });
    aggregator.drain(sum);

    assertEquals((long) threads * perThread, total[0]);
    assertTrue(aggregator.isEmpty());
  }

  // ---- Helpers ----

  /** A drained row. */
  private static final class Row {
    final ConcurrentFlagAggregator.Bucket bucket;
    final long count;
    final long first;
    final long last;
    final boolean defaultUsed;

    Row(
        final ConcurrentFlagAggregator.Bucket bucket,
        final long count,
        final long first,
        final long last,
        final boolean defaultUsed) {
      this.bucket = bucket;
      this.count = count;
      this.first = first;
      this.last = last;
      this.defaultUsed = defaultUsed;
    }
  }

  private List<Row> drain() {
    final List<Row> rows = new ArrayList<>();
    final int drained =
        aggregator.drain((bucket, count, first, last, defaultUsed) ->
            rows.add(new Row(bucket, count, first, last, defaultUsed)));
    assertEquals(rows.size(), drained);
    return rows;
  }

  private static List<String> summaries(final List<Row> rows) {
    return rows.stream()
        .map(r -> r.bucket.flagKey + " " + r.count + " " + r.first + "-" + r.last)
        .sorted()
        .collect(toList());
  }

  /** The drained full-tier rows' features and counts, sorted. */
  private List<String> features() {
    return drain().stream()
        .filter(r -> !r.bucket.degraded)
        .map(r -> r.bucket.features + "=" + r.count)
        .sorted()
        .collect(toList());
  }

  /** Fills the full tier to its global cap with distinct buckets, under the per-flag cap. */
  private void fillFullTier() {
    for (int i = 0; i < GLOBAL_CAP; i++) {
      aggregator.record(
          "filler-" + (i % 20), "on", "a", "u" + i, null, 1L, false, emptyMap(), emptyMap());
    }
  }

  private void record(
      final String flagKey, final String variant, final String targetingKey, final long time) {
    aggregator.record(
        flagKey, variant, "alloc", targetingKey, null, time, false, emptyMap(), emptyMap());
  }

  /** Records an evaluation with consent, so its context is a key dimension. */
  private void full(
      final String flagKey, final String targetingKey, final Map<String, Object> attrs) {
    full(flagKey, "on", "alloc", null, targetingKey, attrs);
  }

  private void full(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String errorMessage,
      final String targetingKey,
      final Map<String, Object> attrs) {
    aggregator.record(
        flagKey, variant, allocationKey, targetingKey, errorMessage, 1L, true, attrs, emptyMap());
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

  /** Runs producers on few keys, calling {@code meanwhile} repeatedly until they finish. */
  private void runProducers(final int threads, final int perThread, final Runnable meanwhile)
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
                    (i & 1) == 0 ? "on" : null,
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
}

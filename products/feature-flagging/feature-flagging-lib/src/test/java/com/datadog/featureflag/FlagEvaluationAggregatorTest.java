package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagEvaluationAggregatorTest {

  @Test
  void identicalEventsAggregateIntoOneBucketWithCount2() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();

    aggregator.aggregate(event("flag-a", "on", "alloc1", "user-1", 1000L, emptyMap()));
    aggregator.aggregate(event("flag-a", "on", "alloc1", "user-1", 2000L, emptyMap()));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(1, state.fullTier.size());
    final FlagEvaluationAggregator.EvalBucket bucket = state.fullTier.values().iterator().next();
    assertEquals(2, bucket.count);
    assertEquals(1000L, bucket.firstEvalMs);
    assertEquals(2000L, bucket.lastEvalMs);
    assertTrue(bucket.firstEvalMs <= bucket.lastEvalMs);
  }

  @Test
  void differentValueTypesProduceDifferentBuckets() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    final Map<String, Object> attrsInt = new HashMap<>();
    attrsInt.put("score", 1);
    final Map<String, Object> attrsStr = new HashMap<>();
    attrsStr.put("score", "1");

    aggregator.aggregate(event("flag-b", "on", "alloc1", "user-1", 1000L, true, attrsInt));
    aggregator.aggregate(event("flag-b", "on", "alloc1", "user-1", 1000L, true, attrsStr));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(2, state.fullTier.size());
  }

  @Test
  void nulCharactersInKeyFieldsDoNotCollide() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();

    aggregator.aggregate(event("a\0b", "c", "alloc1", "user-1", 1000L, emptyMap()));
    aggregator.aggregate(event("a", "b\0c", "alloc1", "user-1", 1000L, emptyMap()));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(2, state.fullTier.size());
  }

  @Test
  void globalCapOverflowRoutesToDegradedTier() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();

    aggregator.simulateFullTierAtCap();
    aggregator.aggregate(simpleEvent("extra-flag", "on"));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertTrue(state.degradedTier.size() > 0);
    assertEquals(0, state.droppedDegradedOverflow);
  }

  @Test
  void degradedCapOverflowIncrementsDroppedCounter() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();

    aggregator.simulateFullTierAtCap();
    aggregator.simulateDegradedTierAtCap();
    aggregator.aggregate(simpleEvent("drop-flag", "on"));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertTrue(state.droppedDegradedOverflow > 0);
  }

  @Test
  void byteBudgetOverflowRoutesToDegradedTierAndReportsReason() {
    final Map<String, Object> attrs = context(10, 16, 32);
    final FlagEvalEvent event = event("byte-flag", "on", "alloc1", "user-1", 1000L, true, attrs);
    final long fullBucketBytes =
        FlagEvaluationMemoryEstimator.fullBucketBytes(
            event, attrs, FlagEvaluationAggregator.canonicalContextKey(attrs));
    final long degradedBucketBytes = FlagEvaluationMemoryEstimator.degradedBucketBytes(event);
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator(fullBucketBytes - 1);

    aggregator.aggregate(event);

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(0, state.fullTier.size());
    assertEquals(1, state.degradedTier.size());
    assertEquals(1, state.degradedByteBudget);
    assertEquals(0, state.degradedCardinalityCap);
    assertEquals(0, state.droppedByteBudget);
    assertEquals(degradedBucketBytes, state.retainedBytes);
  }

  @Test
  void byteBudgetDropsNewDegradedBucketWhenNoSpaceRemains() {
    final Map<String, Object> attrs = context(10, 16, 32);
    final FlagEvalEvent event =
        event("drop-byte-flag", "on", "alloc1", "user-1", 1000L, true, attrs);
    final long degradedBucketBytes = FlagEvaluationMemoryEstimator.degradedBucketBytes(event);
    final FlagEvaluationAggregator aggregator =
        new FlagEvaluationAggregator(degradedBucketBytes - 1);

    aggregator.aggregate(event);

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertTrue(state.fullTier.isEmpty());
    assertTrue(state.degradedTier.isEmpty());
    assertEquals(1, state.droppedByteBudget);
    assertEquals(0, state.degradedByteBudget);
    assertEquals(0, state.retainedBytes);
  }

  @Test
  void existingBucketMergesWithoutReservingMoreBytes() {
    final Map<String, Object> attrs = context(10, 16, 32);
    final FlagEvalEvent event =
        event("merge-byte-flag", "on", "alloc1", "user-1", 1000L, true, attrs);
    final long fullBucketBytes =
        FlagEvaluationMemoryEstimator.fullBucketBytes(
            event, attrs, FlagEvaluationAggregator.canonicalContextKey(attrs));
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator(fullBucketBytes);

    aggregator.aggregate(event);
    aggregator.aggregate(event);

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(1, state.fullTier.size());
    assertEquals(2, state.fullTier.values().iterator().next().count);
    assertEquals(fullBucketBytes, state.retainedBytes);
  }

  @Test
  void clearReleasesRetainedByteBudget() {
    final FlagEvalEvent event = simpleEvent("clear-byte-flag", "on");
    final long fullBucketBytes = FlagEvaluationMemoryEstimator.fullBucketBytes(event, null, "");
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator(fullBucketBytes);

    aggregator.aggregate(event);
    assertEquals(fullBucketBytes, aggregator.retainedBytes());

    aggregator.clear();
    assertEquals(0, aggregator.retainedBytes());
    aggregator.aggregate(event);
    assertEquals(1, aggregator.fullTierSize());
  }

  @Test
  void estimatorCoversMeasuredJolProfiles() {
    final Map<String, Object> typical = context(256, 16, 32);
    final FlagEvalEvent typicalEvent =
        event("flag", "on", "allocation", fixedLength("subject", 64), 1L, true, typical);
    final Map<String, Object> maximum = context(256, 256, 256);
    final FlagEvalEvent maximumEvent =
        event("flag", "on", "allocation", fixedLength("subject", 64), 1L, true, maximum);
    final FlagEvalEvent protectedEvent =
        event("flag", "on", "allocation", fixedLength("subject", 64), 1L, false, maximum);
    final Map<String, Object> nullValue = new HashMap<>();
    nullValue.put("nullable", null);
    final FlagEvalEvent nullValueEvent =
        event("flag", "on", "allocation", "subject", 1L, true, nullValue);

    assertTrue(
        FlagEvaluationMemoryEstimator.fullBucketBytes(
                typicalEvent, typical, FlagEvaluationAggregator.canonicalContextKey(typical))
            >= 60_021);
    assertTrue(
        FlagEvaluationMemoryEstimator.fullBucketBytes(
                maximumEvent, maximum, FlagEvaluationAggregator.canonicalContextKey(maximum))
            >= 297_589);
    assertTrue(FlagEvaluationMemoryEstimator.fullBucketBytes(protectedEvent, null, "") >= 253);
    assertTrue(
        FlagEvaluationMemoryEstimator.fullBucketBytes(
                nullValueEvent, nullValue, FlagEvaluationAggregator.canonicalContextKey(nullValue))
            > 0);
  }

  @Test
  void perFlagCapOverflowRoutesToDegradedTierAndMergesSameDegradedKey() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    aggregator.perFlagCount.put("hot-flag", FlagEvaluationAggregator.PER_FLAG_CAP);

    aggregator.aggregate(event("hot-flag", "on", "alloc1", "user-1", 1000L, emptyMap()));
    aggregator.aggregate(event("hot-flag", "on", "alloc1", "user-2", 2000L, emptyMap()));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(0, state.fullTier.size());
    assertEquals(1, state.degradedTier.size());
    final FlagEvaluationAggregator.EvalBucket bucket =
        state.degradedTier.values().iterator().next();
    assertEquals(2, bucket.count);
    assertEquals(1000L, bucket.firstEvalMs);
    assertEquals(2000L, bucket.lastEvalMs);
  }

  @Test
  void mixedConsentDegradedEvaluationsMergeIntoOneBucket() {
    // Mirror of mixedConsentEvaluationsForSameSubjectLandInDistinctBuckets, but for the degraded
    // tier: the wire serializer for degraded rows drops the targeting key and context regardless
    // of consent, so two events differing only in consent emit byte-identical JSON. They must
    // share a bucket, otherwise DEGRADED_CAP is effectively halved for zero wire fidelity gain.
    // The AND-fold on EvalBucket.observeFullEvaluationData still runs but the value has no
    // downstream effect for degraded rows.
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    aggregator.perFlagCount.put("hot-flag", FlagEvaluationAggregator.PER_FLAG_CAP);

    aggregator.aggregate(event("hot-flag", "on", "alloc1", "user-1", 1000L, true, emptyMap()));
    aggregator.aggregate(event("hot-flag", "on", "alloc1", "user-2", 2000L, false, emptyMap()));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(0, state.fullTier.size());
    assertEquals(1, state.degradedTier.size());
    final FlagEvaluationAggregator.EvalBucket bucket =
        state.degradedTier.values().iterator().next();
    assertEquals(2, bucket.count);
    // AND-fold collapses to consent-off; benign for degraded rows but a documented invariant.
    assertFalse(bucket.observeFullEvaluationData);
  }

  @Test
  void absentVariantSetsRuntimeDefaultUsed() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();

    aggregator.aggregate(event("flag-c", null, "alloc1", "user-1", 1000L, emptyMap()));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(1, state.fullTier.size());
    final FlagEvaluationAggregator.EvalBucket bucket = state.fullTier.values().iterator().next();
    assertTrue(bucket.runtimeDefaultUsed);
  }

  @Test
  void aggregatorStoresPrunedAttrsVerbatim() {
    // Hot-path hook (DDEvaluator#copyPrunedContext) now delivers an already-pruned map.
    // The aggregator no longer re-prunes; it stores what it is given.
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    final Map<String, Object> preprunedAttrs = new HashMap<>();
    for (int i = 0; i < 100; i++) {
      preprunedAttrs.put("key" + i, "v" + i);
    }

    aggregator.aggregate(event("flag-d", "on", "alloc1", "user-1", 1000L, true, preprunedAttrs));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    final FlagEvaluationAggregator.EvalBucket bucket = state.fullTier.values().iterator().next();
    assertEquals(100, bucket.prunedContextFieldCount());
    assertEquals(100, bucket.prunedAttrs.size());
  }

  @Test
  void emptyContextInputsProduceEmptyCanonicalKey() {
    assertEquals("", FlagEvaluationAggregator.canonicalContextKey(null));
    assertEquals("", FlagEvaluationAggregator.canonicalContextKey(emptyMap()));
  }

  @Test
  void canonicalContextKeyEncodesSupportedValueTypes() {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("bool", true);
    attrs.put("double", 1.5d);
    attrs.put("float", 1.25f);
    attrs.put("int", 1);
    attrs.put("long", 2L);
    attrs.put("null", null);
    attrs.put("object", new StringBuilder("other"));
    attrs.put("string", "value");

    final String key = FlagEvaluationAggregator.canonicalContextKey(attrs);

    assertEquals(key, FlagEvaluationAggregator.canonicalContextKey(new HashMap<>(attrs)));
    assertTrue(key.contains("bool"));
    assertTrue(key.contains("string"));
    assertTrue(key.contains("other"));
  }

  @Test
  void aggregatorStoresPrePrunedAttrsWithoutRePruning() {
    // Value-length pruning moved to DDEvaluator#copyPrunedContext (hot path). The aggregator
    // stores what it is given, so any long value present in the input remains present.
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    final Map<String, Object> preprunedAttrs = new HashMap<>();
    preprunedAttrs.put("short-val", "ok");

    aggregator.aggregate(event("flag-e", "on", "alloc1", "user-1", 1000L, true, preprunedAttrs));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    final FlagEvaluationAggregator.EvalBucket bucket = state.fullTier.values().iterator().next();
    assertTrue(bucket.prunedAttrs.containsKey("short-val"));
  }

  @Test
  void capSizingUsesNamedScaleConstants() {
    assertEquals(125_000, FlagEvaluationAggregator.FULL_BUCKET_SIZING_BASIS);
    assertEquals(10_000, FlagEvaluationAggregator.PER_FLAG_BUCKET_SIZING_BASIS);
    assertEquals(25_000, FlagEvaluationAggregator.DEGRADED_BUCKET_SIZING_BASIS);
    assertEquals(131_072, FlagEvaluationAggregator.GLOBAL_CAP);
    assertEquals(10_000, FlagEvaluationAggregator.PER_FLAG_CAP);
    assertEquals(32_768, FlagEvaluationAggregator.DEGRADED_CAP);
    assertEquals(64L << 20, FlagEvaluationAggregator.RETAINED_BYTE_BUDGET);
  }

  @Test
  void flagEvalEventDoesNotCarryReason() {
    final boolean hasReasonField =
        Arrays.stream(
                datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent.class
                    .getDeclaredFields())
            .anyMatch(field -> field.getName().equals("reason"));

    assertFalse(hasReasonField);
  }

  @Test
  void evalBucketTracksBoundsDefaultStateAndNullContextFieldCount() {
    final FlagEvaluationAggregator.EvalBucket bucket =
        new FlagEvaluationAggregator.EvalBucket(
            "bucket-flag", "on", "alloc1", "user-1", null, 1000L, false, null, false);

    assertEquals(0, bucket.prunedContextFieldCount());

    bucket.merge(900L, true);
    bucket.merge(1100L, false);
    bucket.merge(1000L, false);

    assertEquals(4, bucket.count);
    assertEquals(900L, bucket.firstEvalMs);
    assertEquals(1100L, bucket.lastEvalMs);
    assertTrue(bucket.runtimeDefaultUsed);
  }

  @Test
  void fullKeyEqualityUsesEveryDimension() {
    final FlagEvaluationAggregator.FullKey base =
        fullKey("flag", "on", "alloc", false, "error", "user", "ctx");
    final FlagEvaluationAggregator.FullKey same =
        fullKey("flag", "on", "alloc", false, "error", "user", "ctx");

    assertEquals(base, base);
    assertEquals(base, same);
    assertEquals(base.hashCode(), same.hashCode());
    assertNotEquals(base, null);
    assertNotEquals(base, "not-a-key");
    assertNotEquals(base, fullKey("other", "on", "alloc", false, "error", "user", "ctx"));
    assertNotEquals(base, fullKey("flag", "off", "alloc", false, "error", "user", "ctx"));
    assertNotEquals(base, fullKey("flag", "on", "other", false, "error", "user", "ctx"));
    assertNotEquals(base, fullKey("flag", "on", "alloc", true, "error", "user", "ctx"));
    assertNotEquals(base, fullKey("flag", "on", "alloc", false, "other", "user", "ctx"));
    assertNotEquals(base, fullKey("flag", "on", "alloc", false, "error", "other", "ctx"));
    assertNotEquals(base, fullKey("flag", "on", "alloc", false, "error", "user", "other"));
  }

  @Test
  void degradedKeyEqualityUsesEveryDimension() {
    final FlagEvaluationAggregator.DegradedKey base =
        degradedKey("flag", "on", "alloc", false, "error");
    final FlagEvaluationAggregator.DegradedKey same =
        degradedKey("flag", "on", "alloc", false, "error");

    assertEquals(base, base);
    assertEquals(base, same);
    assertEquals(base.hashCode(), same.hashCode());
    assertNotEquals(base, null);
    assertNotEquals(base, "not-a-key");
    assertNotEquals(base, degradedKey("other", "on", "alloc", false, "error"));
    assertNotEquals(base, degradedKey("flag", "off", "alloc", false, "error"));
    assertNotEquals(base, degradedKey("flag", "on", "other", false, "error"));
    assertNotEquals(base, degradedKey("flag", "on", "alloc", true, "error"));
    assertNotEquals(base, degradedKey("flag", "on", "alloc", false, "other"));
  }

  @Test
  void mixedConsentEvaluationsForSameSubjectLandInDistinctBuckets() {
    // Consent is part of FullKey: two evaluations that differ only in consent produce different
    // wire rows (raw vs hashed targeting key, context vs no context) and belong in different
    // buckets. Merging them would silently downgrade the consent-on row to the protected shape.
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 1000L, true, emptyMap()));
    aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 2000L, false, emptyMap()));

    assertEquals(2, aggregator.fullTierSize());
    int onCount = 0;
    int offCount = 0;
    for (final FlagEvaluationAggregator.EvalBucket bucket :
        aggregator.snapshot().fullTier.values()) {
      if (bucket.observeFullEvaluationData) {
        onCount++;
      } else {
        offCount++;
      }
    }
    assertEquals(1, onCount);
    assertEquals(1, offCount);
  }

  @Test
  void sameConsentEvaluationsForSameSubjectMergeIntoOneBucket() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 1000L, true, emptyMap()));
    aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 2000L, true, emptyMap()));

    final FlagEvaluationAggregator.EvalBucket bucket =
        aggregator.snapshot().fullTier.values().iterator().next();
    assertEquals(2, bucket.count);
    assertTrue(bucket.observeFullEvaluationData);
  }

  @Test
  void protectedPathCollapsesDifferingContextIntoOneBucket() {
    // Same subject, different request-id contexts, consent off: the context is dropped on emit so
    // it must not fragment full-tier buckets or the per-flag cap blows out under real traffic.
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    final Map<String, Object> ctx1 = new HashMap<>();
    ctx1.put("request_id", "req-1");
    final Map<String, Object> ctx2 = new HashMap<>();
    ctx2.put("request_id", "req-2");
    final Map<String, Object> ctx3 = new HashMap<>();
    ctx3.put("request_id", "req-3");

    aggregator.aggregate(event("checkout", "on", "alloc1", "alice", 1000L, false, ctx1));
    aggregator.aggregate(event("checkout", "on", "alloc1", "alice", 2000L, false, ctx2));
    aggregator.aggregate(event("checkout", "on", "alloc1", "alice", 3000L, false, ctx3));

    assertEquals(1, aggregator.fullTierSize());
    final FlagEvaluationAggregator.EvalBucket bucket =
        aggregator.snapshot().fullTier.values().iterator().next();
    assertEquals(3, bucket.count);
    assertFalse(bucket.observeFullEvaluationData);
    assertEquals(0, bucket.prunedContextFieldCount());
  }

  @Test
  void protectedPathSeparatesDifferentSubjects() {
    // Different targeting keys must still fall into distinct buckets on the protected path — the
    // (hashed) targeting key stays part of the aggregation identity.
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    aggregator.aggregate(event("checkout", "on", "alloc1", "alice", 1000L, false, emptyMap()));
    aggregator.aggregate(event("checkout", "on", "alloc1", "bob", 2000L, false, emptyMap()));

    assertEquals(2, aggregator.fullTierSize());
  }

  @Test
  void fullPathStillSplitsBucketsOnDifferingContext() {
    // Consent-on preserves the previous behaviour: distinct contexts remain distinct buckets so
    // each raw context is emitted verbatim.
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    final Map<String, Object> ctx1 = new HashMap<>();
    ctx1.put("plan", "pro");
    ctx1.put("request_id", "req-1");
    final Map<String, Object> ctx2 = new HashMap<>();
    ctx2.put("plan", "pro");
    ctx2.put("request_id", "req-2");

    aggregator.aggregate(event("checkout", "on", "alloc1", "alice", 1000L, true, ctx1));
    aggregator.aggregate(event("checkout", "on", "alloc1", "alice", 2000L, true, ctx2));

    assertEquals(2, aggregator.fullTierSize());
  }

  private static FlagEvalEvent event(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      final Map<String, Object> attrs) {
    return new FlagEvalEvent(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        null,
        evalTimeMs,
        observeFullEvaluationData,
        attrs);
  }

  private static FlagEvalEvent event(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final long evalTimeMs,
      final Map<String, Object> attrs) {
    return new FlagEvalEvent(flagKey, variant, allocationKey, targetingKey, evalTimeMs, attrs);
  }

  private static FlagEvalEvent simpleEvent(final String flagKey, final String variant) {
    return event(flagKey, variant, "alloc1", "user-1", 1000L, emptyMap());
  }

  private static Map<String, Object> context(
      final int fieldCount, final int keyLength, final int valueLength) {
    final Map<String, Object> attrs = new HashMap<>();
    for (int field = 0; field < fieldCount; field++) {
      attrs.put(fixedLength("key-" + field, keyLength), fixedLength("value-" + field, valueLength));
    }
    return attrs;
  }

  private static String fixedLength(final String prefix, final int length) {
    final char[] value = new char[length];
    final int prefixLength = Math.min(prefix.length(), length);
    prefix.getChars(0, prefixLength, value, 0);
    Arrays.fill(value, prefixLength, length, 'x');
    return new String(value);
  }

  private static FlagEvaluationAggregator.FullKey fullKey(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final boolean runtimeDefaultUsed,
      final String errorMessage,
      final String targetingKey,
      final String contextKey) {
    return new FlagEvaluationAggregator.FullKey(
        flagKey,
        variant,
        allocationKey,
        runtimeDefaultUsed,
        errorMessage,
        targetingKey,
        contextKey,
        false);
  }

  private static FlagEvaluationAggregator.DegradedKey degradedKey(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final boolean runtimeDefaultUsed,
      final String errorMessage) {
    return new FlagEvaluationAggregator.DegradedKey(
        flagKey, variant, allocationKey, runtimeDefaultUsed, errorMessage);
  }
}

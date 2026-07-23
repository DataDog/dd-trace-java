package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
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

    aggregator.aggregate(event("flag-b", "on", "alloc1", "user-1", 1000L, attrsInt));
    aggregator.aggregate(event("flag-b", "on", "alloc1", "user-1", 1000L, attrsStr));

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
  void absentVariantSetsRuntimeDefaultUsed() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();

    aggregator.aggregate(event("flag-c", null, "alloc1", "user-1", 1000L, emptyMap()));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    assertEquals(1, state.fullTier.size());
    final FlagEvaluationAggregator.EvalBucket bucket = state.fullTier.values().iterator().next();
    assertTrue(bucket.runtimeDefaultUsed);
  }

  @Test
  void contextExceeding256FieldsIsPrunedToStoredPrunedAttrs() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    final Map<String, Object> hugeAttrs = new HashMap<>();
    for (int i = 0; i < 300; i++) {
      hugeAttrs.put("key" + i, "v" + i);
    }

    aggregator.aggregate(event("flag-d", "on", "alloc1", "user-1", 1000L, hugeAttrs));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    final FlagEvaluationAggregator.EvalBucket bucket = state.fullTier.values().iterator().next();
    assertEquals(256, bucket.prunedContextFieldCount());
    assertEquals(256, bucket.prunedAttrs.size());
  }

  @Test
  void pruningIsDeterministicSortBeforeCut() {
    final Map<String, Object> attrs = new HashMap<>();
    for (int i = 0; i < 300; i++) {
      attrs.put(String.format("k%03d", i), "v" + i);
    }

    final Map<String, Object> p1 = FlagEvaluationAggregator.pruneContext(attrs);
    final Map<String, Object> p2 = FlagEvaluationAggregator.pruneContext(new HashMap<>(attrs));

    assertEquals(256, p1.size());
    assertEquals(p1.keySet(), p2.keySet());
    assertTrue(p1.containsKey("k000"));
    assertTrue(p1.containsKey("k255"));
    assertFalse(p1.containsKey("k256"));
    assertFalse(p1.containsKey("k299"));
  }

  @Test
  void emptyContextInputsProduceEmptyPrunedMapAndCanonicalKey() {
    assertEquals(emptyMap(), FlagEvaluationAggregator.pruneContext(null));
    assertEquals(emptyMap(), FlagEvaluationAggregator.pruneContext(emptyMap()));
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
  void contextValueExceeding256CharsIsSkippedFromPrunedAttrs() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("long-val", repeat('x', 300));
    attrs.put("short-val", "ok");

    aggregator.aggregate(event("flag-e", "on", "alloc1", "user-1", 1000L, attrs));

    final FlagEvaluationAggregator.AggregatedState state = aggregator.snapshot();
    final FlagEvaluationAggregator.EvalBucket bucket = state.fullTier.values().iterator().next();
    assertFalse(bucket.prunedAttrs.containsKey("long-val"));
    assertTrue(bucket.prunedAttrs.containsKey("short-val"));
  }

  @Test
  void capSizingUsesNamedScaleConstants() {
    assertEquals(125_000, FlagEvaluationAggregator.EVAL_SCALE_FULL_BUCKET_TARGET);
    assertEquals(10_000, FlagEvaluationAggregator.EVAL_SCALE_PER_FLAG_BUCKET_TARGET);
    assertEquals(25_000, FlagEvaluationAggregator.EVAL_SCALE_DEGRADED_BUCKET_TARGET);
    assertEquals(131_072, FlagEvaluationAggregator.GLOBAL_CAP);
    assertEquals(10_000, FlagEvaluationAggregator.PER_FLAG_CAP);
    assertEquals(32_768, FlagEvaluationAggregator.DEGRADED_CAP);
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
  void observeFullEvaluationDataFoldsToFalseWhenAnyMergedEvaluationLacksConsent() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    try {
      FeatureFlaggingGateway.dispatch(observeConfig(true));
      aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 1000L, emptyMap()));
      // A later RC update turns consent off; the second evaluation folds into the same bucket.
      FeatureFlaggingGateway.dispatch(observeConfig(false));
      aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 2000L, emptyMap()));

      final FlagEvaluationAggregator.EvalBucket bucket =
          aggregator.snapshot().fullTier.values().iterator().next();
      assertEquals(2, bucket.count);
      // Conservative fold: one no-consent evaluation sinks the whole bucket to hashed/omitted.
      assertFalse(bucket.observeFullEvaluationData);
    } finally {
      FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    }
  }

  @Test
  void observeFullEvaluationDataStaysTrueWhenEveryMergedEvaluationConsents() {
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    try {
      FeatureFlaggingGateway.dispatch(observeConfig(true));
      aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 1000L, emptyMap()));
      aggregator.aggregate(event("fold-flag", "on", "alloc1", "user-1", 2000L, emptyMap()));

      final FlagEvaluationAggregator.EvalBucket bucket =
          aggregator.snapshot().fullTier.values().iterator().next();
      assertEquals(2, bucket.count);
      assertTrue(bucket.observeFullEvaluationData);
    } finally {
      FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    }
  }

  private static ServerConfiguration observeConfig(final boolean observeFullEvaluationData) {
    return new ServerConfiguration(
        "2024-04-17T19:40:53.716Z",
        "SERVER",
        observeFullEvaluationData,
        null,
        java.util.Collections.emptyMap());
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
        contextKey);
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

  private static String repeat(final char c, final int count) {
    final char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}

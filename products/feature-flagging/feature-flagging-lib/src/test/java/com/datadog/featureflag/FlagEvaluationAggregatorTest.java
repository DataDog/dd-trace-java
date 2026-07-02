package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  private static String repeat(final char c, final int count) {
    final char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}

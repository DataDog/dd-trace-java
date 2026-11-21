package com.datadog.openfeature.internal.flagevaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * The tier matching strategies compare every dimension after the hash. Real events essentially
 * never collide on the 64-bit hash, so these tests give buckets the lookup's hash and change one
 * dimension at a time.
 */
class FlagEvaluationAggregatorTiersTest {
  private static final FlagEvalEvent EVENT =
      new FlagEvalEvent("flag", "variant", "allocation", "subject", "error", 1L, true, null);

  @TableTest({
    "dimension  | matches",
    "none       | true   ",
    "hash       | false  ",
    "consent    | false  ",
    "flag       | false  ",
    "variant    | false  ",
    "allocation | false  ",
    "error      | false  ",
    "subject    | false  ",
    "context    | false  ",
    "features   | false  "
  })
  void fullTierComparesEveryDimension(final String dimension, final boolean matches) {
    final FlagEvaluationAggregator.Lookup lookup =
        new FlagEvaluationAggregator.Lookup().of(EVENT, "context", "features");
    final FlagEvaluationAggregator.EvalBucket bucket =
        bucket(lookup.fullTierHash(), dimension, true);

    assertEquals(
        matches, new FlagEvaluationAggregator.FullTier().matches(bucket, lookup), dimension);
  }

  @TableTest({
    "dimension  | matches",
    "none       | true   ",
    "hash       | false  ",
    "flag       | false  ",
    "variant    | false  ",
    "allocation | false  ",
    "error      | false  ",
    "features   | false  ",
    "consent    | true   ",
    "subject    | true   ",
    "context    | true   "
  })
  void degradedTierIgnoresTheSubjectContextAndConsent(
      final String dimension, final boolean matches) {
    final FlagEvaluationAggregator.Lookup lookup =
        new FlagEvaluationAggregator.Lookup().of(EVENT, "context", "features");
    final FlagEvaluationAggregator.EvalBucket bucket =
        bucket(lookup.degradedTierHash(), dimension, false);

    assertEquals(
        matches, new FlagEvaluationAggregator.DegradedTier().matches(bucket, lookup), dimension);
  }

  @Test
  void bucketsSkipEmptySlotsAndEndWithNoSuchElement() {
    final FlagEvaluationAggregator.EvalBucket only = bucket(1L, "none", true);
    final FlagEvaluationAggregator.Buckets buckets =
        new FlagEvaluationAggregator.Buckets(
            new FlagEvaluationAggregator.EvalBucket[] {null, only, null});

    assertTrue(buckets.hasNext());
    assertSame(only, buckets.next());
    assertFalse(buckets.hasNext());
    assertThrows(NoSuchElementException.class, buckets::next);
  }

  /** A bucket equal to {@link #EVENT} in every dimension except {@code differs}. */
  private static FlagEvaluationAggregator.EvalBucket bucket(
      final long hash, final String differs, final boolean fullTier) {
    final String other = "other";
    return new FlagEvaluationAggregator.EvalBucket(
        "hash".equals(differs) ? hash + 1 : hash,
        "flag".equals(differs) ? other : EVENT.flagKey,
        "variant".equals(differs) ? other : EVENT.variant,
        "allocation".equals(differs) ? other : EVENT.allocationKey,
        "subject".equals(differs) ? other : EVENT.targetingKey,
        "error".equals(differs) ? other : EVENT.errorMessage,
        1L,
        false,
        null,
        "consent".equals(differs)
            ? !EVENT.observeFullEvaluationData
            : EVENT.observeFullEvaluationData,
        null,
        "context".equals(differs) ? other : "context",
        "features".equals(differs) ? other : "features");
  }
}

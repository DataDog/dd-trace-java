package com.datadog.openfeature.internal.flagevaluation;

import datadog.trace.util.ConcurrentHashtable;
import datadog.trace.util.LongHashingUtils;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nullable;

/**
 * Aggregates flag evaluations into rows on the evaluating threads, for the worker to flush.
 *
 * <p><b>Tiers.</b> A full-tier tally is keyed by every dimension: flag, variant, allocation, error,
 * subject, context, consent and features. Without consent the context is dropped on emit, so it
 * neither splits tallies nor is stored. The full tier holds at most {@link #GLOBAL_CAP} tallies,
 * and {@link #PER_FLAG_CAP} per flag. Past either cap an evaluation spills to the degraded tier,
 * keyed without the subject, the context and the consent (degraded rows never carry them), and
 * AND-folds the consent of the evaluations it merges. The degraded tier holds at most {@link
 * #DEGRADED_CAP} tallies; past it an evaluation is dropped and counted in {@link
 * #droppedDegradedOverflow}.
 *
 * <p><b>Recording.</b> Each tier is a toolbox {@link ConcurrentHashtable}. An evaluation that lands
 * in an existing tally costs a lock-free chain scan and a few atomic updates, and allocates
 * nothing. A new tally is inserted under the table's write lock, which also enforces the tier's
 * cap. The per-flag cap is checked before that lock, so racing inserts can overshoot it by a few
 * tallies.
 *
 * <p><b>Drained in place.</b> Tallies live across flushes. A tally whose count goes from zero to
 * one pushes itself onto a lock-free stack of the tallies touched since the last drain. {@link
 * #drain} takes the whole stack and, for each tally, reads and resets its counters. An evaluation
 * racing the drain is never lost: if its increment lands before the reset, this drain emits it; if
 * after, it finds the count at zero and pushes the tally onto the next drain's stack. Only its
 * timestamps can land in the other flush. A drain costs time in proportion to the tallies touched,
 * not to the table, and busy tallies are not recreated every window.
 *
 * <p><b>Idle sweep.</b> {@link #sweepIdle} removes the tallies idle for a whole window, which frees
 * their slots under the caps. An evaluation that finds a tally just as it is swept still counts, in
 * the next drain; a later one creates a fresh tally.
 */
final class ConcurrentFlagAggregator {
  // Design assumptions: the scale the caps are sized for.
  static final int EXPECTED_FLAG_COUNT = 2_500;
  static final int EXPECTED_FULL_BUCKETS_PER_FLAG = 50;
  static final int EXPECTED_USERS_PER_FLAG = 1_000;
  static final int PER_FLAG_HEADROOM_MULTIPLIER = 10;
  static final int EXPECTED_DEGRADED_BUCKETS_PER_FLAG = 10;

  // Derived sizing: the math behind the caps below.
  static final int FULL_BUCKET_SIZING_BASIS =
      EXPECTED_FLAG_COUNT * EXPECTED_FULL_BUCKETS_PER_FLAG; // 125_000
  static final int PER_FLAG_BUCKET_SIZING_BASIS =
      PER_FLAG_HEADROOM_MULTIPLIER * EXPECTED_USERS_PER_FLAG; // 10_000
  static final int DEGRADED_BUCKET_SIZING_BASIS =
      EXPECTED_FLAG_COUNT * EXPECTED_DEGRADED_BUCKETS_PER_FLAG; // 25_000

  // Enforced tally caps.
  static final int GLOBAL_CAP = 131_072; // nearest power of two above FULL_BUCKET_SIZING_BASIS
  static final int PER_FLAG_CAP = PER_FLAG_BUCKET_SIZING_BASIS;
  static final int DEGRADED_CAP = 32_768; // nearest power of two above DEGRADED_BUCKET_SIZING_BASIS

  /**
   * The most flags whose full-tier tallies are counted. Counts are never removed, so the
   * evaluations of further flags go to the degraded tier.
   */
  static final int FLAG_COUNT_CAP = 65_536;

  /** Receives each drained row, with the counters it had at the drain. */
  interface Rows {
    void row(
        EvaluationTally tally, long count, long firstEvalMs, long lastEvalMs, boolean defaultUsed);
  }

  private static final AtomicReferenceFieldUpdater<ConcurrentFlagAggregator, EvaluationTally>
      DIRTY =
          AtomicReferenceFieldUpdater.newUpdater(
              ConcurrentFlagAggregator.class, EvaluationTally.class, "dirty");

  final AtomicLong droppedDegradedOverflow = new AtomicLong();
  private final ConcurrentHashtable.State<EvaluationTally> full =
      ConcurrentHashtable.createBounded(EvaluationTally.class, GLOBAL_CAP);
  private final ConcurrentHashtable.State<EvaluationTally> degraded =
      ConcurrentHashtable.createBounded(EvaluationTally.class, DEGRADED_CAP);
  private final ConcurrentHashtable.D1<String, FlagCount> flagCounts =
      ConcurrentHashtable.D1.createBounded(FlagCount.class, FLAG_COUNT_CAP);

  // The tallies touched since the last drain, linked through EvaluationTally.dirtyNext.
  @Nullable private volatile EvaluationTally dirty;
  // Written by the draining thread only; read when a tally is created.
  private volatile int drains;

  /** Records one evaluation, on the evaluating thread. */
  void record(
      final String flagKey,
      @Nullable final String variant,
      @Nullable final String allocationKey,
      @Nullable final String targetingKey,
      @Nullable final String errorMessage,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      @Nullable final Map<String, Object> attrs,
      @Nullable final Map<String, Object> features) {
    final boolean isDefault = variant == null;
    // Without consent the context is dropped on emit, so it must not fragment tallies or be
    // stored: a high-cardinality field (request_id, timestamp) would blow out PER_FLAG_CAP and
    // spill every later evaluation into the degraded tier.
    final Map<String, Object> context = observeFullEvaluationData ? attrs : null;
    final long contextHash = observeFullEvaluationData ? contextHash(context) : 0L;
    // Features come from the flag configuration, not the evaluation context, so they split
    // tallies in both tiers and whatever the consent.
    final Map<String, Object> featureMap =
        features == null ? Collections.<String, Object>emptyMap() : features;
    final long featuresHash = featureMap.hashCode();

    final long fullHash =
        fullHash(
            flagKey,
            variant,
            allocationKey,
            errorMessage,
            targetingKey,
            contextHash,
            observeFullEvaluationData,
            featuresHash);
    for (EvaluationTally b = ConcurrentHashtable.bucketFor(full, fullHash);
        b != null;
        b = b.next()) {
      if (b.keyHash == fullHash
          && b.matchesFull(
              flagKey,
              variant,
              allocationKey,
              errorMessage,
              targetingKey,
              context,
              observeFullEvaluationData,
              featureMap)) {
        b.merge(this, evalTimeMs, isDefault);
        return;
      }
    }

    final FlagCount flagCount = flagCounts.tryGetOrCreateOrNull(flagKey, FlagCount::new);
    if (flagCount != null && flagCount.count.get() < PER_FLAG_CAP) {
      final EvaluationTally tally =
          new EvaluationTally(
              false,
              fullHash,
              flagKey,
              variant,
              allocationKey,
              targetingKey,
              errorMessage,
              context,
              observeFullEvaluationData,
              featureMap,
              flagCount,
              drains);
      final EvaluationTally inserted;
      try (ConcurrentHashtable.Reservation<EvaluationTally> reservation =
          ConcurrentHashtable.tryReserve(full, fullHash)) {
        inserted = reservation.tryGetOrInsertOrNull(tally);
      }
      if (inserted != null) {
        if (inserted == tally) {
          flagCount.count.incrementAndGet();
        }
        inserted.merge(this, evalTimeMs, isDefault);
        return;
      }
      // The full tier is at its global cap: fall through to the degraded tier.
    }

    final long degradedHash =
        degradedHash(flagKey, variant, allocationKey, errorMessage, featuresHash);
    for (EvaluationTally b = ConcurrentHashtable.bucketFor(degraded, degradedHash);
        b != null;
        b = b.next()) {
      if (b.keyHash == degradedHash
          && b.matchesDegraded(flagKey, variant, allocationKey, errorMessage, featureMap)) {
        b.mergeDegraded(this, evalTimeMs, isDefault, observeFullEvaluationData);
        return;
      }
    }
    final EvaluationTally tally =
        new EvaluationTally(
            true,
            degradedHash,
            flagKey,
            variant,
            allocationKey,
            null,
            errorMessage,
            null,
            observeFullEvaluationData,
            featureMap,
            null,
            drains);
    final EvaluationTally inserted;
    try (ConcurrentHashtable.Reservation<EvaluationTally> reservation =
        ConcurrentHashtable.tryReserve(degraded, degradedHash)) {
      inserted = reservation.tryGetOrInsertOrNull(tally);
    }
    if (inserted == null) {
      droppedDegradedOverflow.incrementAndGet();
    } else {
      inserted.mergeDegraded(this, evalTimeMs, isDefault, observeFullEvaluationData);
    }
  }

  private void push(final EvaluationTally tally) {
    EvaluationTally head;
    do {
      head = dirty;
      tally.dirtyNext = head;
    } while (!DIRTY.compareAndSet(this, head, tally));
  }

  /** Whether no evaluation has been recorded since the last drain. */
  boolean isEmpty() {
    return dirty == null;
  }

  /**
   * Passes every tally touched since the last drain to {@code rows}, with its counters, and resets
   * them. One draining thread at a time.
   *
   * <p>A row that throws does not stop the drain: every touched tally is still read and reset, so
   * none is left counting without being on the stack. The first failure is rethrown at the end.
   *
   * @return the number of rows drained.
   */
  int drain(final Rows rows) {
    final int drain = drains + 1;
    drains = drain;
    int drained = 0;
    RuntimeException failure = null;
    EvaluationTally b = DIRTY.getAndSet(this, null);
    while (b != null) {
      // Unlink before resetting the count: once it is zero, an evaluation can push the tally
      // again, and that push writes dirtyNext.
      final EvaluationTally next = b.dirtyNext;
      b.dirtyNext = null;
      final long count = EvaluationTally.COUNT.getAndSet(b, 0);
      final long first = EvaluationTally.FIRST.getAndSet(b, Long.MAX_VALUE);
      final long last = EvaluationTally.LAST.getAndSet(b, Long.MIN_VALUE);
      final boolean defaultUsed = EvaluationTally.DEFAULT_USED.getAndSet(b, 0) != 0;
      b.lastDrain = drain;
      if (count > 0) {
        drained++;
        try {
          rows.row(b, count, first, last, defaultUsed);
        } catch (RuntimeException e) {
          if (failure == null) {
            failure = e;
          }
        }
      }
      b = next;
    }
    if (failure != null) {
      throw failure;
    }
    return drained;
  }

  /**
   * Removes the tallies that saw no evaluation in the last whole window, freeing their slots under
   * the caps. The draining thread only, after {@link #drain}.
   *
   * @return the number of tallies removed.
   */
  int sweepIdle() {
    final int drain = drains;
    final int[] removed = {0};
    ConcurrentHashtable.removeIf(
        full,
        b -> {
          if (b.isIdleSince(drain)) {
            b.flagCount.count.decrementAndGet();
            removed[0]++;
            return true;
          }
          return false;
        });
    ConcurrentHashtable.removeIf(
        degraded,
        b -> {
          if (b.isIdleSince(drain)) {
            removed[0]++;
            return true;
          }
          return false;
        });
    return removed[0];
  }

  /** The number of tallies in both tiers, touched or not. */
  int tallyCount() {
    return ConcurrentHashtable.estimateSize(full) + ConcurrentHashtable.estimateSize(degraded);
  }

  static long fullHash(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String errorMessage,
      final String targetingKey,
      final long contextHash,
      final boolean observeFullEvaluationData,
      final long featuresHash) {
    long hash = LongHashingUtils.hash(flagKey, variant, allocationKey, errorMessage, targetingKey);
    hash = LongHashingUtils.addToHash(hash, contextHash);
    hash = LongHashingUtils.addToHash(hash, observeFullEvaluationData);
    return LongHashingUtils.addToHash(hash, featuresHash);
  }

  static long degradedHash(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String errorMessage,
      final long featuresHash) {
    final long hash = LongHashingUtils.hash(flagKey, variant, allocationKey, errorMessage);
    return LongHashingUtils.addToHash(hash, featuresHash);
  }

  /**
   * Hashes a context the way {@link #sameContext} compares it: independent of iteration order, and
   * distinguishing value types (Integer 1, Long 1 and Double 1.0 hash and compare apart).
   *
   * <p>Entries are combined by addition, so the order the map yields them in does not matter; each
   * entry is mixed first, so that swapping values between keys changes the hash.
   */
  static long contextHash(@Nullable final Map<String, Object> context) {
    if (context == null || context.isEmpty()) {
      return 0L;
    }
    long hash = context.size();
    for (final Map.Entry<String, Object> entry : context.entrySet()) {
      final Object value = entry.getValue();
      final long valueHash =
          value == null ? 0L : (((long) value.getClass().hashCode()) << 32) ^ value.hashCode();
      hash += LongHashingUtils.unorderedEntryHash(entry.getKey().hashCode(), valueHash);
    }
    return hash;
  }

  /**
   * Whether two contexts would be emitted the same. A missing context and an empty one are the
   * same, and values compare with {@code equals}, which already tells value types apart.
   */
  static boolean sameContext(
      @Nullable final Map<String, Object> a, @Nullable final Map<String, Object> b) {
    if (a == b) {
      return true;
    }
    if (a == null || a.isEmpty()) {
      return b == null || b.isEmpty();
    }
    return a.equals(b);
  }

  /** One aggregated row: its key, and counters updated by any evaluating thread. */
  static final class EvaluationTally extends ConcurrentHashtable.Entry<EvaluationTally> {
    static final AtomicLongFieldUpdater<EvaluationTally> COUNT =
        AtomicLongFieldUpdater.newUpdater(EvaluationTally.class, "count");
    static final AtomicLongFieldUpdater<EvaluationTally> FIRST =
        AtomicLongFieldUpdater.newUpdater(EvaluationTally.class, "firstEvalMs");
    static final AtomicLongFieldUpdater<EvaluationTally> LAST =
        AtomicLongFieldUpdater.newUpdater(EvaluationTally.class, "lastEvalMs");
    static final AtomicIntegerFieldUpdater<EvaluationTally> DEFAULT_USED =
        AtomicIntegerFieldUpdater.newUpdater(EvaluationTally.class, "defaultUsed");

    final boolean degraded;
    final String flagKey;
    @Nullable final String variant;
    @Nullable final String allocationKey;
    @Nullable final String targetingKey;
    @Nullable final String errorMessage;
    @Nullable final Map<String, Object> prunedAttrs;
    final Map<String, Object> features;
    // The full-tier tally count of this tally's flag; null for degraded tallies.
    @Nullable final FlagCount flagCount;
    // Fixed for a full-tier tally, where consent is a key dimension. A degraded tally AND-folds
    // it, which is benign: degraded rows drop the targeting key and context whatever the consent.
    volatile boolean observeFullEvaluationData;

    volatile long count;
    volatile long firstEvalMs = Long.MAX_VALUE;
    volatile long lastEvalMs = Long.MIN_VALUE;
    volatile int defaultUsed;

    // The next tally on the stack of tallies touched since the last drain.
    @Nullable EvaluationTally dirtyNext;
    // The drain that last found this tally touched, or the last drain before it was created.
    int lastDrain;

    EvaluationTally(
        final boolean degraded,
        final long keyHash,
        final String flagKey,
        @Nullable final String variant,
        @Nullable final String allocationKey,
        @Nullable final String targetingKey,
        @Nullable final String errorMessage,
        @Nullable final Map<String, Object> prunedAttrs,
        final boolean observeFullEvaluationData,
        final Map<String, Object> features,
        @Nullable final FlagCount flagCount,
        final int lastDrain) {
      super(keyHash);
      this.lastDrain = lastDrain;
      this.degraded = degraded;
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.targetingKey = targetingKey;
      this.errorMessage = errorMessage;
      this.prunedAttrs = prunedAttrs;
      this.features = features;
      this.flagCount = flagCount;
      this.observeFullEvaluationData = observeFullEvaluationData;
    }

    void merge(
        final ConcurrentFlagAggregator owner, final long evalTimeMs, final boolean isDefault) {
      // Timestamps first, so a drain that counts this evaluation also sees its timestamps.
      updateTimes(evalTimeMs, isDefault);
      if (COUNT.getAndIncrement(this) == 0) {
        owner.push(this);
        // A drain may have reset the timestamps between the update above and the increment.
        updateTimes(evalTimeMs, isDefault);
      }
    }

    void mergeDegraded(
        final ConcurrentFlagAggregator owner,
        final long evalTimeMs,
        final boolean isDefault,
        final boolean observeFullEvaluationData) {
      if (!observeFullEvaluationData && this.observeFullEvaluationData) {
        this.observeFullEvaluationData = false; // a mixed tally stays at consent-off
      }
      merge(owner, evalTimeMs, isDefault);
    }

    /** Widens the time range to {@code evalTimeMs}, and marks a runtime default. */
    private void updateTimes(final long evalTimeMs, final boolean isDefault) {
      long first;
      while (evalTimeMs < (first = firstEvalMs) && !FIRST.compareAndSet(this, first, evalTimeMs)) {
        // retry: another thread moved it
      }
      long last;
      while (evalTimeMs > (last = lastEvalMs) && !LAST.compareAndSet(this, last, evalTimeMs)) {
        // retry: another thread moved it
      }
      if (isDefault && defaultUsed == 0) {
        defaultUsed = 1;
      }
    }

    /** Untouched through the whole window that ended with drain {@code drain}. */
    boolean isIdleSince(final int drain) {
      return count == 0 && drain - lastDrain >= 1;
    }

    boolean matchesFull(
        final String flagKey,
        @Nullable final String variant,
        @Nullable final String allocationKey,
        @Nullable final String errorMessage,
        @Nullable final String targetingKey,
        @Nullable final Map<String, Object> context,
        final boolean observeFullEvaluationData,
        final Map<String, Object> features) {
      return this.observeFullEvaluationData == observeFullEvaluationData
          && this.flagKey.equals(flagKey)
          && Objects.equals(this.variant, variant)
          && Objects.equals(this.allocationKey, allocationKey)
          && Objects.equals(this.errorMessage, errorMessage)
          && Objects.equals(this.targetingKey, targetingKey)
          && this.features.equals(features)
          && sameContext(this.prunedAttrs, context);
    }

    boolean matchesDegraded(
        final String flagKey,
        @Nullable final String variant,
        @Nullable final String allocationKey,
        @Nullable final String errorMessage,
        final Map<String, Object> features) {
      return this.flagKey.equals(flagKey)
          && Objects.equals(this.variant, variant)
          && Objects.equals(this.allocationKey, allocationKey)
          && Objects.equals(this.errorMessage, errorMessage)
          && this.features.equals(features);
    }

    @Override
    public boolean matches(final EvaluationTally other) {
      return degraded
          ? matchesDegraded(
              other.flagKey, other.variant, other.allocationKey, other.errorMessage, other.features)
          : matchesFull(
              other.flagKey,
              other.variant,
              other.allocationKey,
              other.errorMessage,
              other.targetingKey,
              other.prunedAttrs,
              other.observeFullEvaluationData,
              other.features);
    }
  }

  /** The number of full-tier tallies of a flag. */
  static final class FlagCount extends ConcurrentHashtable.D1.Entry<String> {
    final AtomicInteger count = new AtomicInteger();

    FlagCount(final String flagKey) {
      super(flagKey);
    }
  }
}

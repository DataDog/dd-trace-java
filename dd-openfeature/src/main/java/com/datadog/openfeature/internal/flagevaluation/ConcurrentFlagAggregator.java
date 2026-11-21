package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationAggregator.DEGRADED_CAP;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationAggregator.GLOBAL_CAP;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationAggregator.PER_FLAG_CAP;

import datadog.trace.util.ConcurrentHashtable;
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
 * PROTOTYPE: aggregates flag evaluations on the evaluating threads, into toolbox {@link
 * ConcurrentHashtable}s, instead of queueing an event per evaluation for one worker thread.
 *
 * <p>An evaluation that lands in an existing bucket costs a lock-free chain scan and a few atomic
 * updates, and allocates nothing. A new bucket is inserted under the table's write lock, which also
 * enforces the tier's cap. The tiers, caps and bucket keys are the same as {@link
 * FlagEvaluationAggregator}'s, so the payloads don't change.
 *
 * <p><b>Drained in place.</b> Buckets live across flushes. A bucket whose count goes from zero to
 * one pushes itself onto a lock-free stack of the buckets touched since the last flush. The flush
 * takes the whole stack and, for each bucket, reads and resets its counters. An evaluation racing
 * the flush is never lost: if its increment lands before the reset, this flush emits it; if after,
 * it finds the count at zero and pushes the bucket onto the next flush's stack. Only the timestamps
 * of such an evaluation can land in the other flush. So a flush costs time in proportion to the
 * buckets touched, not to the table, and busy buckets aren't recreated every window.
 *
 * <p>Buckets idle for a whole window are swept out, which frees their slots under the caps. An
 * evaluation that finds a bucket just as it's swept still counts, in the next flush; a later one
 * creates a fresh bucket.
 */
final class ConcurrentFlagAggregator {
  /** Receives each drained row, with the counters it had at the flush. */
  interface Rows {
    void row(Bucket bucket, long count, long firstEvalMs, long lastEvalMs, boolean defaultUsed);
  }

  private static final AtomicReferenceFieldUpdater<ConcurrentFlagAggregator, Bucket> DIRTY =
      AtomicReferenceFieldUpdater.newUpdater(ConcurrentFlagAggregator.class, Bucket.class, "dirty");

  private static final int FLAG_COUNT_CAP = 65_536;

  final AtomicLong droppedDegradedOverflow = new AtomicLong();
  final ConcurrentHashtable.State<Bucket> full =
      ConcurrentHashtable.createBounded(Bucket.class, GLOBAL_CAP);
  final ConcurrentHashtable.State<Bucket> degraded =
      ConcurrentHashtable.createBounded(Bucket.class, DEGRADED_CAP);
  private final ConcurrentHashtable.D1<String, FlagCount> flagCounts =
      ConcurrentHashtable.D1.createBounded(FlagCount.class, FLAG_COUNT_CAP);

  // The buckets touched since the last drain, linked through Bucket.dirtyNext.
  @Nullable private volatile Bucket dirty;
  // Written by the worker only; read when a bucket is created.
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
    // As in FlagEvaluationAggregator: without consent the context is dropped on emit, so it must
    // neither split buckets nor be stored.
    final Map<String, Object> context = observeFullEvaluationData ? attrs : null;
    final long contextHash =
        observeFullEvaluationData ? FlagEvaluationAggregator.contextHash(context) : 0L;
    final Map<String, Object> featureMap =
        features == null ? Collections.<String, Object>emptyMap() : features;
    final long featuresHash = featureMap.hashCode();

    final long fullHash =
        FlagEvaluationAggregator.fullHash(
            flagKey,
            variant,
            allocationKey,
            errorMessage,
            targetingKey,
            contextHash,
            observeFullEvaluationData,
            featuresHash);
    for (Bucket b = ConcurrentHashtable.bucketFor(full, fullHash); b != null; b = b.next()) {
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
      final Bucket bucket =
          new Bucket(
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
      final Bucket inserted;
      try (ConcurrentHashtable.Reservation<Bucket> reservation =
          ConcurrentHashtable.tryReserve(full, fullHash)) {
        inserted = reservation.tryGetOrInsertOrNull(bucket);
      }
      if (inserted != null) {
        if (inserted == bucket) {
          flagCount.count.incrementAndGet();
        }
        inserted.merge(this, evalTimeMs, isDefault);
        return;
      }
      // The full tier is at its global cap: fall through to the degraded tier.
    }

    final long degradedHash =
        FlagEvaluationAggregator.degradedHash(
            flagKey, variant, allocationKey, errorMessage, featuresHash);
    for (Bucket b = ConcurrentHashtable.bucketFor(degraded, degradedHash);
        b != null;
        b = b.next()) {
      if (b.keyHash == degradedHash
          && b.matchesDegraded(flagKey, variant, allocationKey, errorMessage, featureMap)) {
        b.mergeDegraded(this, evalTimeMs, isDefault, observeFullEvaluationData);
        return;
      }
    }
    final Bucket bucket =
        new Bucket(
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
    final Bucket inserted;
    try (ConcurrentHashtable.Reservation<Bucket> reservation =
        ConcurrentHashtable.tryReserve(degraded, degradedHash)) {
      inserted = reservation.tryGetOrInsertOrNull(bucket);
    }
    if (inserted == null) {
      droppedDegradedOverflow.incrementAndGet();
    } else {
      inserted.mergeDegraded(this, evalTimeMs, isDefault, observeFullEvaluationData);
    }
  }

  private void push(final Bucket bucket) {
    Bucket head;
    do {
      head = dirty;
      bucket.dirtyNext = head;
    } while (!DIRTY.compareAndSet(this, head, bucket));
  }

  /** Whether no evaluation has been recorded since the last drain. */
  boolean isEmpty() {
    return dirty == null;
  }

  /**
   * Passes every bucket touched since the last drain to {@code rows}, with its counters, and resets
   * them. Worker thread only.
   *
   * @return the number of rows drained.
   */
  int drain(final Rows rows) {
    final int drain = drains + 1;
    drains = drain;
    int drained = 0;
    Bucket b = DIRTY.getAndSet(this, null);
    while (b != null) {
      // Unlink before resetting the count: once it is zero, an evaluation can push the bucket
      // again, and that push writes dirtyNext.
      final Bucket next = b.dirtyNext;
      b.dirtyNext = null;
      final long count = Bucket.COUNT.getAndSet(b, 0);
      final long first = Bucket.FIRST.getAndSet(b, Long.MAX_VALUE);
      final long last = Bucket.LAST.getAndSet(b, Long.MIN_VALUE);
      final boolean defaultUsed = Bucket.DEFAULT_USED.getAndSet(b, 0) != 0;
      b.lastDrain = drain;
      if (count > 0) {
        drained++;
        rows.row(b, count, first, last, defaultUsed);
      }
      b = next;
    }
    return drained;
  }

  /**
   * Removes the buckets that saw no evaluation in the last whole window, freeing their slots under
   * the caps. Worker thread only, after {@link #drain}.
   *
   * @return the number of buckets removed.
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

  int bucketCount() {
    return ConcurrentHashtable.estimateSize(full) + ConcurrentHashtable.estimateSize(degraded);
  }

  /** One aggregated row: its key, and counters updated by any evaluating thread. */
  static final class Bucket extends ConcurrentHashtable.Entry<Bucket> {
    static final AtomicLongFieldUpdater<Bucket> COUNT =
        AtomicLongFieldUpdater.newUpdater(Bucket.class, "count");
    static final AtomicLongFieldUpdater<Bucket> FIRST =
        AtomicLongFieldUpdater.newUpdater(Bucket.class, "firstEvalMs");
    static final AtomicLongFieldUpdater<Bucket> LAST =
        AtomicLongFieldUpdater.newUpdater(Bucket.class, "lastEvalMs");
    static final AtomicIntegerFieldUpdater<Bucket> DEFAULT_USED =
        AtomicIntegerFieldUpdater.newUpdater(Bucket.class, "defaultUsed");

    final boolean degraded;
    final String flagKey;
    @Nullable final String variant;
    @Nullable final String allocationKey;
    @Nullable final String targetingKey;
    @Nullable final String errorMessage;
    @Nullable final Map<String, Object> prunedAttrs;
    final Map<String, Object> features;
    // The full-tier bucket count of this bucket's flag; null for degraded buckets.
    @Nullable final FlagCount flagCount;
    // Fixed for a full-tier bucket (consent is a key dimension); degraded buckets AND-fold it.
    volatile boolean observeFullEvaluationData;

    volatile long count;
    volatile long firstEvalMs = Long.MAX_VALUE;
    volatile long lastEvalMs = Long.MIN_VALUE;
    volatile int defaultUsed;

    // The next bucket on the stack of buckets touched since the last drain.
    @Nullable Bucket dirtyNext;
    // The drain that last found this bucket touched, or the last drain before it was created.
    int lastDrain;

    Bucket(
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
      updateTimes(evalTimeMs);
      if (isDefault && defaultUsed == 0) {
        defaultUsed = 1;
      }
      if (COUNT.getAndIncrement(this) == 0) {
        owner.push(this);
        // A drain may have reset the timestamps between the update above and the increment.
        updateTimes(evalTimeMs);
      }
    }

    void mergeDegraded(
        final ConcurrentFlagAggregator owner,
        final long evalTimeMs,
        final boolean isDefault,
        final boolean observeFullEvaluationData) {
      if (!observeFullEvaluationData && this.observeFullEvaluationData) {
        this.observeFullEvaluationData = false; // a mixed bucket stays at consent-off
      }
      merge(owner, evalTimeMs, isDefault);
    }

    private void updateTimes(final long evalTimeMs) {
      long first;
      while (evalTimeMs < (first = firstEvalMs) && !FIRST.compareAndSet(this, first, evalTimeMs)) {
        // retry: another thread moved it
      }
      long last;
      while (evalTimeMs > (last = lastEvalMs) && !LAST.compareAndSet(this, last, evalTimeMs)) {
        // retry: another thread moved it
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
          && FlagEvaluationAggregator.sameContext(this.prunedAttrs, context);
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
    public boolean matches(final Bucket other) {
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

  /** The number of full-tier buckets of a flag. */
  static final class FlagCount extends ConcurrentHashtable.D1.Entry<String> {
    final AtomicInteger count = new AtomicInteger();

    FlagCount(final String flagKey) {
      super(flagKey);
    }
  }
}

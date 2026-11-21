package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationAggregator.DEGRADED_CAP;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationAggregator.GLOBAL_CAP;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationAggregator.PER_FLAG_CAP;

import datadog.trace.util.ConcurrentHashtable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
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
 * <p>The buckets of a flush window live in a {@link Window}. At flush, the worker swaps in a spare
 * window, waits a short grace period for evaluations already updating the old one, serializes it,
 * then clears it as the next spare. An update that lands in the old window after the grace period
 * is lost; that's best-effort, like the queue it replaces, whose overflow drops whole events.
 */
final class ConcurrentFlagAggregator {
  /** How long a swapped-out window stays untouched before it's read. */
  static final long GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

  private static final int FLAG_COUNT_CAP = 65_536;
  private static final int FEATURE_SET_CAP = 65_536;

  final AtomicLong droppedDegradedOverflow = new AtomicLong();
  private volatile Window current = new Window();
  @Nullable private Window spare = new Window(); // worker thread only

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
    final Window window = current;
    final boolean isDefault = variant == null;
    // As in FlagEvaluationAggregator: without consent the context is dropped on emit, so it must
    // neither split buckets nor be stored.
    final Map<String, Object> prunedAttrs = observeFullEvaluationData ? attrs : null;
    final String contextKey =
        observeFullEvaluationData ? FlagEvaluationAggregator.canonicalContextKey(prunedAttrs) : "";
    final FeatureSet featureSet = window.featureSet(features);

    final long fullHash =
        FlagEvaluationAggregator.fullHash(
            flagKey,
            variant,
            allocationKey,
            errorMessage,
            targetingKey,
            contextKey,
            observeFullEvaluationData,
            featureSet.key);
    for (Bucket b = ConcurrentHashtable.bucketFor(window.full, fullHash); b != null; b = b.next()) {
      if (b.keyHash == fullHash
          && b.matchesFull(
              flagKey,
              variant,
              allocationKey,
              errorMessage,
              targetingKey,
              contextKey,
              observeFullEvaluationData,
              featureSet.key)) {
        b.merge(evalTimeMs, isDefault);
        return;
      }
    }

    final FlagCount flagCount = window.flagCounts.tryGetOrCreateOrNull(flagKey, FlagCount::new);
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
              evalTimeMs,
              isDefault,
              prunedAttrs,
              observeFullEvaluationData,
              featureSet,
              contextKey);
      final Bucket inserted;
      try (ConcurrentHashtable.Reservation<Bucket> reservation =
          ConcurrentHashtable.tryReserve(window.full, fullHash)) {
        inserted = reservation.tryGetOrInsertOrNull(bucket);
      }
      if (inserted == bucket) {
        flagCount.count.incrementAndGet();
        return;
      }
      if (inserted != null) {
        inserted.merge(evalTimeMs, isDefault); // another thread inserted it first
        return;
      }
      // The full tier is at its global cap: fall through to the degraded tier.
    }

    final long degradedHash =
        FlagEvaluationAggregator.degradedHash(
            flagKey, variant, allocationKey, errorMessage, featureSet.key);
    for (Bucket b = ConcurrentHashtable.bucketFor(window.degraded, degradedHash);
        b != null;
        b = b.next()) {
      if (b.keyHash == degradedHash
          && b.matchesDegraded(flagKey, variant, allocationKey, errorMessage, featureSet.key)) {
        b.merge(evalTimeMs, isDefault);
        if (!observeFullEvaluationData) {
          b.observeFullEvaluationData = false; // a mixed bucket stays at consent-off
        }
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
            evalTimeMs,
            isDefault,
            null,
            observeFullEvaluationData,
            featureSet,
            "");
    final Bucket inserted;
    try (ConcurrentHashtable.Reservation<Bucket> reservation =
        ConcurrentHashtable.tryReserve(window.degraded, degradedHash)) {
      inserted = reservation.tryGetOrInsertOrNull(bucket);
    }
    if (inserted == null) {
      droppedDegradedOverflow.incrementAndGet();
    } else if (inserted != bucket) {
      inserted.merge(evalTimeMs, isDefault);
    }
  }

  /** Whether the current window has no buckets. */
  boolean isEmpty() {
    return current.isEmpty();
  }

  /**
   * Swaps in a fresh window and returns the old one, once evaluations already updating it have had
   * a grace period to finish. Worker thread only; give the window back with {@link #recycle}.
   */
  Window swap() {
    final Window old = current;
    final Window next = spare != null ? spare : new Window();
    spare = null;
    current = next;
    LockSupport.parkNanos(GRACE_NANOS);
    return old;
  }

  /** Clears a window returned by {@link #swap} and keeps it as the next spare. */
  void recycle(final Window window) {
    window.clear();
    spare = window;
  }

  /** The buckets of one flush window. */
  static final class Window {
    final ConcurrentHashtable.State<Bucket> full =
        ConcurrentHashtable.createBounded(Bucket.class, GLOBAL_CAP);
    final ConcurrentHashtable.State<Bucket> degraded =
        ConcurrentHashtable.createBounded(Bucket.class, DEGRADED_CAP);
    final ConcurrentHashtable.D1<String, FlagCount> flagCounts =
        ConcurrentHashtable.D1.createBounded(FlagCount.class, FLAG_COUNT_CAP);
    final ConcurrentHashtable.D1<String, FeatureSet> featureSets =
        ConcurrentHashtable.D1.createBounded(FeatureSet.class, FEATURE_SET_CAP);

    FeatureSet featureSet(@Nullable final Map<String, Object> features) {
      if (features == null || features.isEmpty()) {
        return FeatureSet.NONE;
      }
      final String key = FlagEvaluationAggregator.canonicalContextKey(features);
      final FeatureSet featureSet =
          featureSets.tryGetOrCreateOrNull(key, k -> new FeatureSet(k, features));
      // At the feature-set cap, keep the features unshared rather than drop the evaluation.
      return featureSet != null ? featureSet : new FeatureSet(key, features);
    }

    boolean isEmpty() {
      return ConcurrentHashtable.estimateSize(full) == 0
          && ConcurrentHashtable.estimateSize(degraded) == 0;
    }

    void forEachFull(final Consumer<Bucket> consumer) {
      ConcurrentHashtable.forEach(full, consumer);
    }

    void forEachDegraded(final Consumer<Bucket> consumer) {
      ConcurrentHashtable.forEach(degraded, consumer);
    }

    long degradedEvaluationCount() {
      final long[] count = {0};
      ConcurrentHashtable.forEach(degraded, bucket -> count[0] += bucket.count);
      return count[0];
    }

    int bucketCount() {
      return ConcurrentHashtable.estimateSize(full) + ConcurrentHashtable.estimateSize(degraded);
    }

    void clear() {
      ConcurrentHashtable.clear(full);
      ConcurrentHashtable.clear(degraded);
      flagCounts.clear();
      featureSets.clear();
    }
  }

  /** One aggregated row: its key, and counters updated by any evaluating thread. */
  static final class Bucket extends ConcurrentHashtable.Entry<Bucket> {
    private static final AtomicLongFieldUpdater<Bucket> COUNT =
        AtomicLongFieldUpdater.newUpdater(Bucket.class, "count");
    private static final AtomicLongFieldUpdater<Bucket> FIRST =
        AtomicLongFieldUpdater.newUpdater(Bucket.class, "firstEvalMs");
    private static final AtomicLongFieldUpdater<Bucket> LAST =
        AtomicLongFieldUpdater.newUpdater(Bucket.class, "lastEvalMs");

    final boolean degraded;
    final String flagKey;
    @Nullable final String variant;
    @Nullable final String allocationKey;
    @Nullable final String targetingKey;
    @Nullable final String errorMessage;
    @Nullable final Map<String, Object> prunedAttrs;
    final Map<String, Object> features;
    final String contextKey;
    final String featuresKey;
    // Fixed for a full-tier bucket (consent is a key dimension); degraded buckets AND-fold it.
    volatile boolean observeFullEvaluationData;
    volatile long count;
    volatile long firstEvalMs;
    volatile long lastEvalMs;
    volatile boolean runtimeDefaultUsed;

    Bucket(
        final boolean degraded,
        final long keyHash,
        final String flagKey,
        @Nullable final String variant,
        @Nullable final String allocationKey,
        @Nullable final String targetingKey,
        @Nullable final String errorMessage,
        final long evalTimeMs,
        final boolean runtimeDefaultUsed,
        @Nullable final Map<String, Object> prunedAttrs,
        final boolean observeFullEvaluationData,
        final FeatureSet featureSet,
        final String contextKey) {
      super(keyHash);
      this.degraded = degraded;
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.targetingKey = targetingKey;
      this.errorMessage = errorMessage;
      this.prunedAttrs = prunedAttrs;
      this.features = featureSet.features;
      this.featuresKey = featureSet.key;
      this.contextKey = contextKey;
      this.observeFullEvaluationData = observeFullEvaluationData;
      this.count = 1;
      this.firstEvalMs = evalTimeMs;
      this.lastEvalMs = evalTimeMs;
      this.runtimeDefaultUsed = runtimeDefaultUsed;
    }

    void merge(final long evalTimeMs, final boolean isDefault) {
      COUNT.incrementAndGet(this);
      long first;
      while (evalTimeMs < (first = firstEvalMs) && !FIRST.compareAndSet(this, first, evalTimeMs)) {
        // retry: another thread moved it
      }
      long last;
      while (evalTimeMs > (last = lastEvalMs) && !LAST.compareAndSet(this, last, evalTimeMs)) {
        // retry: another thread moved it
      }
      if (isDefault && !runtimeDefaultUsed) {
        runtimeDefaultUsed = true;
      }
    }

    boolean matchesFull(
        final String flagKey,
        @Nullable final String variant,
        @Nullable final String allocationKey,
        @Nullable final String errorMessage,
        @Nullable final String targetingKey,
        final String contextKey,
        final boolean observeFullEvaluationData,
        final String featuresKey) {
      return this.observeFullEvaluationData == observeFullEvaluationData
          && this.flagKey.equals(flagKey)
          && Objects.equals(this.variant, variant)
          && Objects.equals(this.allocationKey, allocationKey)
          && Objects.equals(this.errorMessage, errorMessage)
          && Objects.equals(this.targetingKey, targetingKey)
          && this.contextKey.equals(contextKey)
          && this.featuresKey.equals(featuresKey);
    }

    boolean matchesDegraded(
        final String flagKey,
        @Nullable final String variant,
        @Nullable final String allocationKey,
        @Nullable final String errorMessage,
        final String featuresKey) {
      return this.flagKey.equals(flagKey)
          && Objects.equals(this.variant, variant)
          && Objects.equals(this.allocationKey, allocationKey)
          && Objects.equals(this.errorMessage, errorMessage)
          && this.featuresKey.equals(featuresKey);
    }

    @Override
    public boolean matches(final Bucket other) {
      return degraded
          ? matchesDegraded(
              other.flagKey,
              other.variant,
              other.allocationKey,
              other.errorMessage,
              other.featuresKey)
          : matchesFull(
              other.flagKey,
              other.variant,
              other.allocationKey,
              other.errorMessage,
              other.targetingKey,
              other.contextKey,
              other.observeFullEvaluationData,
              other.featuresKey);
    }
  }

  /** The number of full-tier buckets of a flag in the window. */
  static final class FlagCount extends ConcurrentHashtable.D1.Entry<String> {
    final AtomicInteger count = new AtomicInteger();

    FlagCount(final String flagKey) {
      super(flagKey);
    }
  }

  /** One shared key and map per distinct feature set in the window. */
  static final class FeatureSet extends ConcurrentHashtable.D1.Entry<String> {
    static final FeatureSet NONE = new FeatureSet("", Collections.<String, Object>emptyMap());

    final String key;
    final Map<String, Object> features;

    FeatureSet(final String key, final Map<String, Object> features) {
      super(key);
      this.key = key;
      this.features = features;
    }
  }
}

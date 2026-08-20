package com.datadog.featureflag;

import static datadog.trace.util.HashingUtils.addToHash;
import static datadog.trace.util.HashingUtils.hash;

import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@SuppressFBWarnings(
    value = {"AT_NONATOMIC_64BIT_PRIMITIVE", "AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE"},
    justification = "The aggregator is confined to the single flag-evaluation serializer thread")
final class FlagEvaluationAggregator {

  // Design assumptions — document the scale we sized for
  static final int EXPECTED_FLAG_COUNT = 2_500;
  static final int EXPECTED_FULL_BUCKETS_PER_FLAG = 50;
  static final int EXPECTED_USERS_PER_FLAG = 1_000;
  static final int PER_FLAG_HEADROOM_MULTIPLIER = 10;
  static final int EXPECTED_DEGRADED_BUCKETS_PER_FLAG = 10;

  // Derived sizing — show the math behind the bucket caps below
  static final int FULL_BUCKET_SIZING_BASIS =
      EXPECTED_FLAG_COUNT * EXPECTED_FULL_BUCKETS_PER_FLAG; // 125_000
  static final int PER_FLAG_BUCKET_SIZING_BASIS =
      PER_FLAG_HEADROOM_MULTIPLIER * EXPECTED_USERS_PER_FLAG; // 10_000
  static final int DEGRADED_BUCKET_SIZING_BASIS =
      EXPECTED_FLAG_COUNT * EXPECTED_DEGRADED_BUCKETS_PER_FLAG; // 25_000

  // Enforced bucket caps
  static final int GLOBAL_CAP = 131_072; // nearest power of two above FULL_BUCKET_SIZING_BASIS
  static final int PER_FLAG_CAP = PER_FLAG_BUCKET_SIZING_BASIS;
  static final int DEGRADED_CAP = 32_768; // nearest power of two above DEGRADED_BUCKET_SIZING_BASIS
  static final long RETAINED_BYTE_BUDGET = 64L << 20;

  private static final byte CTX_TAG_STRING = 's';
  private static final byte CTX_TAG_BOOL = 'b';
  private static final byte CTX_TAG_INT = 'i';
  private static final byte CTX_TAG_LONG = 'l';
  private static final byte CTX_TAG_FLOAT = 'f';
  private static final byte CTX_TAG_DOUBLE = 'd';
  private static final byte CTX_TAG_OTHER = 'o';

  final Map<FullKey, EvalBucket> fullTier = new HashMap<>();
  final Map<DegradedKey, EvalBucket> degradedTier = new HashMap<>();
  final Map<String, Integer> perFlagCount = new HashMap<>();
  final AtomicLong droppedDegradedOverflow = new AtomicLong(0);
  final AtomicLong droppedByteBudget = new AtomicLong(0);
  final AtomicLong degradedCardinalityCap = new AtomicLong(0);
  final AtomicLong degradedByteBudget = new AtomicLong(0);
  final AtomicInteger globalFullCount = new AtomicInteger(0);
  private final long retainedByteBudget;
  private long retainedBytes;

  FlagEvaluationAggregator() {
    this(RETAINED_BYTE_BUDGET);
  }

  FlagEvaluationAggregator(final long retainedByteBudget) {
    this.retainedByteBudget = Math.max(0, retainedByteBudget);
  }

  void aggregate(final FlagEvalEvent event) {
    final boolean isDefault = event.variant == null;
    final boolean observeFullEvaluationData = event.observeFullEvaluationData;
    // On the protected path the context is dropped on emit, so it must not fragment buckets or be
    // stored — otherwise a high-cardinality field (request_id, timestamp) blows out PER_FLAG_CAP
    // and spills every subsequent evaluation into the degraded tier.
    final Map<String, Object> prunedAttrs = observeFullEvaluationData ? event.attrs : null;
    final String ctxKey = observeFullEvaluationData ? canonicalContextKey(prunedAttrs) : "";
    final FullKey fullKey = buildFullKey(event, ctxKey);

    EvalBucket bucket = fullTier.get(fullKey);
    if (bucket != null) {
      bucket.merge(event.evalTimeMs, isDefault);
      bucket.observeFullEvaluationData &= observeFullEvaluationData;
      return;
    }

    final int flagCount = perFlagCount.getOrDefault(event.flagKey, 0);
    if (globalFullCount.get() < GLOBAL_CAP && flagCount < PER_FLAG_CAP) {
      final long bucketBytes =
          FlagEvaluationMemoryEstimator.fullBucketBytes(event, prunedAttrs, ctxKey);
      if (reserve(bucketBytes)) {
        fullTier.put(
            fullKey,
            new EvalBucket(
                event.flagKey,
                event.variant,
                event.allocationKey,
                event.targetingKey,
                event.errorMessage,
                event.evalTimeMs,
                isDefault,
                prunedAttrs,
                observeFullEvaluationData));
        globalFullCount.incrementAndGet();
        perFlagCount.put(event.flagKey, flagCount + 1);
        return;
      }
    }

    final boolean degradedByByteBudget =
        globalFullCount.get() < GLOBAL_CAP && flagCount < PER_FLAG_CAP;
    final DegradedKey degradedKey = buildDegradedKey(event);
    bucket = degradedTier.get(degradedKey);
    if (bucket != null) {
      bucket.merge(event.evalTimeMs, isDefault);
      bucket.observeFullEvaluationData &= observeFullEvaluationData;
      countDegraded(degradedByByteBudget);
      return;
    }

    if (degradedTier.size() < DEGRADED_CAP) {
      if (reserve(FlagEvaluationMemoryEstimator.degradedBucketBytes(event))) {
        degradedTier.put(
            degradedKey,
            new EvalBucket(
                event.flagKey,
                event.variant,
                event.allocationKey,
                null,
                event.errorMessage,
                event.evalTimeMs,
                isDefault,
                null,
                observeFullEvaluationData));
        countDegraded(degradedByByteBudget);
        return;
      }
      droppedByteBudget.incrementAndGet();
      return;
    }

    droppedDegradedOverflow.incrementAndGet();
  }

  private void countDegraded(final boolean degradedByByteBudget) {
    if (degradedByByteBudget) {
      degradedByteBudget.incrementAndGet();
    } else {
      degradedCardinalityCap.incrementAndGet();
    }
  }

  private boolean reserve(final long bytes) {
    if (bytes > retainedByteBudget - retainedBytes) {
      return false;
    }
    retainedBytes += bytes;
    return true;
  }

  boolean isEmpty() {
    return fullTier.isEmpty() && degradedTier.isEmpty();
  }

  int fullTierSize() {
    return fullTier.size();
  }

  int bucketCount() {
    return fullTier.size() + degradedTier.size();
  }

  Iterable<EvalBucket> fullBuckets() {
    return fullTier.values();
  }

  Iterable<EvalBucket> degradedBuckets() {
    return degradedTier.values();
  }

  void clear() {
    fullTier.clear();
    degradedTier.clear();
    perFlagCount.clear();
    globalFullCount.set(0);
    retainedBytes = 0;
  }

  long retainedBytes() {
    return retainedBytes;
  }

  AggregatedState snapshot() {
    return new AggregatedState(
        new HashMap<>(fullTier),
        new HashMap<>(degradedTier),
        droppedDegradedOverflow.get(),
        droppedByteBudget.get(),
        degradedCardinalityCap.get(),
        degradedByteBudget.get(),
        retainedBytes);
  }

  void simulateFullTierAtCap() {
    for (int i = globalFullCount.get(); i < GLOBAL_CAP; i++) {
      final String key = "synthetic-full-" + i;
      fullTier.put(
          new FullKey(key, "on", "alloc", false, null, null, "", false),
          new EvalBucket(key, "on", "alloc", null, null, 1L, false, null, false));
      globalFullCount.incrementAndGet();
      perFlagCount.merge(key, 1, Integer::sum);
    }
  }

  void simulateDegradedTierAtCap() {
    for (int i = degradedTier.size(); i < DEGRADED_CAP; i++) {
      final String key = "synthetic-dg-" + i;
      degradedTier.put(
          new DegradedKey(key, "on", "alloc", false, null),
          new EvalBucket(key, "on", "alloc", null, null, 1L, false, null, false));
    }
  }

  void addDegradedBucketForTest(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String errorMessage,
      final long evalTimeMs) {
    degradedTier.put(
        new DegradedKey(flagKey, variant, allocationKey, variant == null, errorMessage),
        new EvalBucket(
            flagKey,
            variant,
            allocationKey,
            null,
            errorMessage,
            evalTimeMs,
            variant == null,
            null,
            false));
  }

  private static FullKey buildFullKey(final FlagEvalEvent event, final String ctxKey) {
    return new FullKey(
        event.flagKey,
        event.variant,
        event.allocationKey,
        event.variant == null,
        event.errorMessage,
        event.targetingKey,
        ctxKey,
        event.observeFullEvaluationData);
  }

  private static DegradedKey buildDegradedKey(final FlagEvalEvent event) {
    return new DegradedKey(
        event.flagKey,
        event.variant,
        event.allocationKey,
        event.variant == null,
        event.errorMessage);
  }

  static String canonicalContextKey(final Map<String, Object> prunedAttrs) {
    if (prunedAttrs == null || prunedAttrs.isEmpty()) {
      return "";
    }
    final Map<String, Object> sorted =
        (prunedAttrs instanceof TreeMap) ? prunedAttrs : new TreeMap<>(prunedAttrs);
    final StringBuilder sb = new StringBuilder();
    for (final Map.Entry<String, Object> entry : sorted.entrySet()) {
      appendLengthDelimited(sb, entry.getKey());
      appendContextValue(sb, entry.getValue());
    }
    return sb.toString();
  }

  private static final String HEX_ZEROS = "00000000";

  private static void appendLengthDelimited(final StringBuilder sb, final String s) {
    // 8-char zero-padded hex length prefix, allocation-lean (no String.format on the hot path).
    final String hexLength = Integer.toHexString(s.length());
    sb.append(HEX_ZEROS, 0, 8 - hexLength.length());
    sb.append(hexLength);
    sb.append(s);
  }

  private static void appendContextValue(final StringBuilder sb, final Object v) {
    if (v instanceof Boolean) {
      sb.append((char) CTX_TAG_BOOL);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Long) {
      sb.append((char) CTX_TAG_LONG);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Integer) {
      sb.append((char) CTX_TAG_INT);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Float) {
      sb.append((char) CTX_TAG_FLOAT);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Double) {
      sb.append((char) CTX_TAG_DOUBLE);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof String) {
      sb.append((char) CTX_TAG_STRING);
      appendLengthDelimited(sb, (String) v);
    } else {
      sb.append((char) CTX_TAG_OTHER);
      appendLengthDelimited(sb, v == null ? "" : v.toString());
    }
  }

  static class EvalBucket {
    long count;
    long firstEvalMs;
    long lastEvalMs;
    boolean runtimeDefaultUsed;
    String flagKey;
    String variant;
    String allocationKey;
    String targetingKey;
    String errorMessage;
    Map<String, Object> prunedAttrs;
    // Consent to emit raw PII. For full-tier buckets this is uniform (consent is a FullKey
    // dimension) and the AND-fold on merge is defensive. For degraded-tier buckets consent is NOT
    // a key dimension — mixed-consent events merge here — so the AND-fold produces false whenever
    // any consent-off event lands in the bucket. That's benign because the degraded wire path
    // drops the targeting key and context regardless of consent, so this field has no downstream
    // effect for degraded rows.
    boolean observeFullEvaluationData;

    EvalBucket(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final String targetingKey,
        final String errorMessage,
        final long evalTimeMs,
        final boolean runtimeDefaultUsed,
        final Map<String, Object> prunedAttrs,
        final boolean observeFullEvaluationData) {
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.targetingKey = targetingKey;
      this.errorMessage = errorMessage;
      this.firstEvalMs = evalTimeMs;
      this.lastEvalMs = evalTimeMs;
      this.count = 1;
      this.runtimeDefaultUsed = runtimeDefaultUsed;
      this.prunedAttrs = prunedAttrs;
      this.observeFullEvaluationData = observeFullEvaluationData;
    }

    int prunedContextFieldCount() {
      return prunedAttrs == null ? 0 : prunedAttrs.size();
    }

    void merge(final long evalTimeMs, final boolean isDefault) {
      count++;
      if (evalTimeMs < firstEvalMs) {
        firstEvalMs = evalTimeMs;
      }
      if (evalTimeMs > lastEvalMs) {
        lastEvalMs = evalTimeMs;
      }
      if (isDefault) {
        runtimeDefaultUsed = true;
      }
    }
  }

  static final class FullKey {
    private final String flagKey;
    private final String variant;
    private final String allocationKey;
    private final boolean runtimeDefaultUsed;
    private final String errorMessage;
    private final String targetingKey;
    private final String contextKey;
    // Part of the key so consent-on and consent-off evaluations never share a bucket. The
    // serializer branches on this to hash the targeting key and drop the context, so events with
    // different consent produce different wire rows and belong in different buckets.
    private final boolean observeFullEvaluationData;

    FullKey(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final boolean runtimeDefaultUsed,
        final String errorMessage,
        final String targetingKey,
        final String contextKey,
        final boolean observeFullEvaluationData) {
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.runtimeDefaultUsed = runtimeDefaultUsed;
      this.errorMessage = errorMessage;
      this.targetingKey = targetingKey;
      this.contextKey = contextKey;
      this.observeFullEvaluationData = observeFullEvaluationData;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FullKey)) {
        return false;
      }
      final FullKey fullKey = (FullKey) o;
      return runtimeDefaultUsed == fullKey.runtimeDefaultUsed
          && observeFullEvaluationData == fullKey.observeFullEvaluationData
          && Objects.equals(flagKey, fullKey.flagKey)
          && Objects.equals(variant, fullKey.variant)
          && Objects.equals(allocationKey, fullKey.allocationKey)
          && Objects.equals(errorMessage, fullKey.errorMessage)
          && Objects.equals(targetingKey, fullKey.targetingKey)
          && Objects.equals(contextKey, fullKey.contextKey);
    }

    @Override
    public int hashCode() {
      // HashingUtils avoids the Object[] allocation and boolean boxing that Objects.hash performs
      // on this hot bucket-lookup path.
      int result = hash(flagKey, variant, allocationKey);
      result = addToHash(result, runtimeDefaultUsed);
      result = addToHash(result, errorMessage);
      result = addToHash(result, targetingKey);
      result = addToHash(result, contextKey);
      return addToHash(result, observeFullEvaluationData);
    }
  }

  static final class DegradedKey {
    // Unlike FullKey, consent is NOT a bucket dimension here: the wire serializer for degraded rows
    // (FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket with isFullTier=false) drops the
    // targeting key and context unconditionally, so two degraded buckets differing only in consent
    // would emit byte-identical JSON with evaluation_count split — halving effective DEGRADED_CAP
    // for zero wire fidelity. Mixed-consent events merge into one bucket; the AND-fold on
    // EvalBucket.observeFullEvaluationData still runs but has no downstream effect for degraded
    // rows.
    private final String flagKey;
    private final String variant;
    private final String allocationKey;
    private final boolean runtimeDefaultUsed;
    private final String errorMessage;

    DegradedKey(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final boolean runtimeDefaultUsed,
        final String errorMessage) {
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.runtimeDefaultUsed = runtimeDefaultUsed;
      this.errorMessage = errorMessage;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof DegradedKey)) {
        return false;
      }
      final DegradedKey that = (DegradedKey) o;
      return runtimeDefaultUsed == that.runtimeDefaultUsed
          && Objects.equals(flagKey, that.flagKey)
          && Objects.equals(variant, that.variant)
          && Objects.equals(allocationKey, that.allocationKey)
          && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
      // HashingUtils avoids the Object[] allocation and boolean boxing that Objects.hash performs
      // on this hot bucket-lookup path.
      int result = hash(flagKey, variant, allocationKey);
      result = addToHash(result, runtimeDefaultUsed);
      return addToHash(result, errorMessage);
    }
  }

  static class AggregatedState {
    final Map<FullKey, EvalBucket> fullTier;
    final Map<DegradedKey, EvalBucket> degradedTier;
    final long droppedDegradedOverflow;
    final long droppedByteBudget;
    final long degradedCardinalityCap;
    final long degradedByteBudget;
    final long retainedBytes;

    AggregatedState(
        final Map<FullKey, EvalBucket> fullTier,
        final Map<DegradedKey, EvalBucket> degradedTier,
        final long droppedDegradedOverflow,
        final long droppedByteBudget,
        final long degradedCardinalityCap,
        final long degradedByteBudget,
        final long retainedBytes) {
      this.fullTier = fullTier;
      this.degradedTier = degradedTier;
      this.droppedDegradedOverflow = droppedDegradedOverflow;
      this.droppedByteBudget = droppedByteBudget;
      this.degradedCardinalityCap = degradedCardinalityCap;
      this.degradedByteBudget = degradedByteBudget;
      this.retainedBytes = retainedBytes;
    }
  }
}

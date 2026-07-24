package com.datadog.featureflag;

import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class FlagEvaluationAggregator {

  static final int EVAL_SCALE_TARGET_FLAGS = 2_500;
  static final int EVAL_SCALE_FULL_BUCKETS_PER_FLAG = 50;
  static final int EVAL_SCALE_USERS_PER_FLAG = 1_000;
  static final int EVAL_SCALE_PER_FLAG_HEADROOM_MULTIPLIER = 10;
  static final int EVAL_SCALE_DEGRADED_BUCKETS_PER_FLAG = 10;
  static final int EVAL_SCALE_FULL_BUCKET_TARGET =
      EVAL_SCALE_TARGET_FLAGS * EVAL_SCALE_FULL_BUCKETS_PER_FLAG;
  static final int EVAL_SCALE_PER_FLAG_BUCKET_TARGET =
      EVAL_SCALE_PER_FLAG_HEADROOM_MULTIPLIER * EVAL_SCALE_USERS_PER_FLAG;
  static final int EVAL_SCALE_DEGRADED_BUCKET_TARGET =
      EVAL_SCALE_TARGET_FLAGS * EVAL_SCALE_DEGRADED_BUCKETS_PER_FLAG;
  static final int GLOBAL_CAP = 131_072;
  static final int PER_FLAG_CAP = EVAL_SCALE_PER_FLAG_BUCKET_TARGET;
  static final int DEGRADED_CAP = 32_768;

  static final int MAX_CONTEXT_FIELDS = 256;
  static final int MAX_FIELD_LENGTH = 256;

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
  final AtomicInteger globalFullCount = new AtomicInteger(0);

  void aggregate(final FlagEvalEvent event) {
    final boolean isDefault = event.variant == null;
    // Consent is read from the event, where it was snapshotted on the evaluation thread at
    // evaluation time. We deliberately do NOT read the gateway here: aggregation runs later, on the
    // serializer thread, by which point a subsequent RC update may have changed CURRENT_CONFIG, and
    // that must not retroactively alter the hashed-vs-raw decision for an already-evaluated flag.
    // Existing buckets fold with AND: one no-consent evaluation sinks the bucket to hashed.
    final boolean observeFullEvaluationData = event.observeFullEvaluationData;
    final Map<String, Object> prunedAttrs = pruneContext(event.contextAttributes());
    final String ctxKey = canonicalContextKey(prunedAttrs);
    final FullKey fullKey = buildFullKey(event, ctxKey);

    EvalBucket bucket = fullTier.get(fullKey);
    if (bucket != null) {
      bucket.merge(event.evalTimeMs, isDefault);
      bucket.observeFullEvaluationData &= observeFullEvaluationData;
      return;
    }

    final int flagCount = perFlagCount.getOrDefault(event.flagKey, 0);
    if (globalFullCount.get() < GLOBAL_CAP && flagCount < PER_FLAG_CAP) {
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

    final DegradedKey degradedKey = buildDegradedKey(event);
    bucket = degradedTier.get(degradedKey);
    if (bucket != null) {
      bucket.merge(event.evalTimeMs, isDefault);
      bucket.observeFullEvaluationData &= observeFullEvaluationData;
      return;
    }

    if (degradedTier.size() < DEGRADED_CAP) {
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
      return;
    }

    droppedDegradedOverflow.incrementAndGet();
  }

  boolean isEmpty() {
    return fullTier.isEmpty() && degradedTier.isEmpty();
  }

  int fullTierSize() {
    return fullTier.size();
  }

  long degradedEvaluationCount() {
    long count = 0;
    for (final EvalBucket bucket : degradedTier.values()) {
      count += bucket.count;
    }
    return count;
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
  }

  AggregatedState snapshot() {
    return new AggregatedState(
        new HashMap<>(fullTier), new HashMap<>(degradedTier), droppedDegradedOverflow.get());
  }

  void simulateFullTierAtCap() {
    for (int i = globalFullCount.get(); i < GLOBAL_CAP; i++) {
      final String key = "synthetic-full-" + i;
      fullTier.put(
          new FullKey(key, "on", "alloc", false, null, null, ""),
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
        ctxKey);
  }

  private static DegradedKey buildDegradedKey(final FlagEvalEvent event) {
    return new DegradedKey(
        event.flagKey,
        event.variant,
        event.allocationKey,
        event.variant == null,
        event.errorMessage);
  }

  static Map<String, Object> pruneContext(final Map<String, Object> attrs) {
    if (attrs == null || attrs.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    final TreeMap<String, Object> out = new TreeMap<>();
    final TreeMap<String, Object> sorted = new TreeMap<>(attrs);
    int count = 0;
    for (final Map.Entry<String, Object> entry : sorted.entrySet()) {
      if (count >= MAX_CONTEXT_FIELDS) {
        break;
      }
      final Object v = entry.getValue();
      if (v instanceof String && ((String) v).length() > MAX_FIELD_LENGTH) {
        continue;
      }
      out.put(entry.getKey(), v);
      count++;
    }
    return out;
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

  private static void appendLengthDelimited(final StringBuilder sb, final String s) {
    sb.append(String.format("%08x", (long) s.length()));
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
    // Consent to emit raw PII (targeting key + context), sourced from each event's evaluation-time
    // snapshot (FlagEvalEvent.observeFullEvaluationData), never read from the gateway at flush. On
    // merge the value is folded with AND (see aggregate()): if any evaluation in the bucket's
    // lifetime saw consent off, the whole bucket falls back to hashed/omitted — fail-closed.
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

    FullKey(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final boolean runtimeDefaultUsed,
        final String errorMessage,
        final String targetingKey,
        final String contextKey) {
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.runtimeDefaultUsed = runtimeDefaultUsed;
      this.errorMessage = errorMessage;
      this.targetingKey = targetingKey;
      this.contextKey = contextKey;
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
          && Objects.equals(flagKey, fullKey.flagKey)
          && Objects.equals(variant, fullKey.variant)
          && Objects.equals(allocationKey, fullKey.allocationKey)
          && Objects.equals(errorMessage, fullKey.errorMessage)
          && Objects.equals(targetingKey, fullKey.targetingKey)
          && Objects.equals(contextKey, fullKey.contextKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          flagKey,
          variant,
          allocationKey,
          runtimeDefaultUsed,
          errorMessage,
          targetingKey,
          contextKey);
    }
  }

  static final class DegradedKey {
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
      return Objects.hash(flagKey, variant, allocationKey, runtimeDefaultUsed, errorMessage);
    }
  }

  static class AggregatedState {
    final Map<FullKey, EvalBucket> fullTier;
    final Map<DegradedKey, EvalBucket> degradedTier;
    final long droppedDegradedOverflow;

    AggregatedState(
        final Map<FullKey, EvalBucket> fullTier,
        final Map<DegradedKey, EvalBucket> degradedTier,
        final long droppedDegradedOverflow) {
      this.fullTier = fullTier;
      this.degradedTier = degradedTier;
      this.droppedDegradedOverflow = droppedDegradedOverflow;
    }
  }
}

package com.datadog.openfeature.internal.flagevaluation;

import datadog.trace.util.FlatHashtable;
import datadog.trace.util.LongHashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregates the evaluations of a flush window into buckets, on the worker thread.
 *
 * <p>Each tier is a toolbox {@link FlatHashtable}, cleared as a whole at every flush. A bucket is
 * its own table entry, carrying its key and the key's hash, and lookups go through one reused
 * {@link Lookup}: a bucket costs one object for the whole window, and aggregating an event into an
 * existing bucket allocates nothing.
 */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
    justification = "the worker thread is the only one aggregating")
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

  private static final int INITIAL_BUCKETS = 64;
  private static final FullTier FULL_TIER = new FullTier();
  private static final DegradedTier DEGRADED_TIER = new DegradedTier();

  private EvalBucket[] fullTier = FlatHashtable.create(INITIAL_BUCKETS, EvalBucket[]::new);
  private int fullTierSize;
  private EvalBucket[] degradedTier = FlatHashtable.create(INITIAL_BUCKETS, EvalBucket[]::new);
  private int degradedTierSize;
  final FlatHashtable.D1<String, FlagCount> perFlagCount =
      FlatHashtable.D1.createGrowable(INITIAL_BUCKETS, FlagCount[]::new);
  final AtomicLong droppedDegradedOverflow = new AtomicLong(0);
  // One shared key and map for each distinct feature set in the flush window, so buckets of many
  // subjects do not each keep their own copy until the flush.
  final FlatHashtable.D1<Map<String, Object>, FeatureSet> featureSets =
      FlatHashtable.D1.createGrowable(INITIAL_BUCKETS, FeatureSet[]::new);
  // The worker is the only thread aggregating, so one lookup serves every event.
  private final Lookup lookup = new Lookup();

  void aggregate(final FlagEvalEvent event) {
    final boolean isDefault = event.variant == null;
    final boolean observeFullEvaluationData = event.observeFullEvaluationData;
    // On the protected path the context is dropped on emit, so it must not fragment buckets or be
    // stored — otherwise a high-cardinality field (request_id, timestamp) blows out PER_FLAG_CAP
    // and spills every subsequent evaluation into the degraded tier.
    final Map<String, Object> prunedAttrs = observeFullEvaluationData ? event.attrs : null;
    final long contextHash = observeFullEvaluationData ? contextHash(prunedAttrs) : 0L;
    // Features come from the flag configuration, not the evaluation context, so they split buckets
    // in both tiers and whatever the consent: rows with different features must not merge.
    final FeatureSet featureSet = featureSet(event.features);
    final Lookup lookup = this.lookup.of(event, prunedAttrs, contextHash, featureSet);

    EvalBucket bucket = FlatHashtable.get(fullTier, lookup, FULL_TIER);
    if (bucket != null) {
      // Consent is a full-tier key dimension, so the bucket's consent is already this event's.
      bucket.merge(event.evalTimeMs, isDefault);
      return;
    }

    final FlagCount flagCount = perFlagCount.get(event.flagKey);
    if (fullTierSize < GLOBAL_CAP && (flagCount == null || flagCount.count < PER_FLAG_CAP)) {
      insertFull(
          new EvalBucket(
              lookup.fullTierHash(),
              event.flagKey,
              event.variant,
              event.allocationKey,
              event.targetingKey,
              event.errorMessage,
              event.evalTimeMs,
              isDefault,
              prunedAttrs,
              observeFullEvaluationData,
              featureSet.features));
      if (flagCount == null) {
        perFlagCount.insert(new FlagCount(event.flagKey, 1));
      } else {
        flagCount.count++;
      }
      return;
    }

    bucket = FlatHashtable.get(degradedTier, lookup, DEGRADED_TIER);
    if (bucket != null) {
      bucket.merge(event.evalTimeMs, isDefault);
      bucket.observeFullEvaluationData &= observeFullEvaluationData;
      return;
    }

    if (degradedTierSize < DEGRADED_CAP) {
      insertDegraded(
          new EvalBucket(
              lookup.degradedTierHash(),
              event.flagKey,
              event.variant,
              event.allocationKey,
              null,
              event.errorMessage,
              event.evalTimeMs,
              isDefault,
              null,
              observeFullEvaluationData,
              featureSet.features));
      return;
    }

    droppedDegradedOverflow.incrementAndGet();
  }

  private void insertFull(final EvalBucket bucket) {
    // Grow at half full: open addressing slows down as the table fills.
    if (fullTierSize >= fullTier.length >> 1) {
      fullTier = FlatHashtable.resize(fullTier, EvalBucket[]::new);
    }
    FlatHashtable.insert(fullTier, bucket);
    fullTierSize++;
  }

  private void insertDegraded(final EvalBucket bucket) {
    if (degradedTierSize >= degradedTier.length >> 1) {
      degradedTier = FlatHashtable.resize(degradedTier, EvalBucket[]::new);
    }
    FlatHashtable.insert(degradedTier, bucket);
    degradedTierSize++;
  }

  boolean isEmpty() {
    return fullTierSize == 0 && degradedTierSize == 0;
  }

  int fullTierSize() {
    return fullTierSize;
  }

  long degradedEvaluationCount() {
    long count = 0;
    for (final EvalBucket bucket : degradedTier) {
      if (bucket != null) {
        count += bucket.count;
      }
    }
    return count;
  }

  int bucketCount() {
    return fullTierSize + degradedTierSize;
  }

  Iterable<EvalBucket> fullBuckets() {
    final EvalBucket[] table = fullTier;
    return () -> new Buckets(table);
  }

  Iterable<EvalBucket> degradedBuckets() {
    final EvalBucket[] table = degradedTier;
    return () -> new Buckets(table);
  }

  void clear() {
    Arrays.fill(fullTier, null);
    fullTierSize = 0;
    Arrays.fill(degradedTier, null);
    degradedTierSize = 0;
    featureSets.clear();
    perFlagCount.clear();
  }

  private FeatureSet featureSet(final Map<String, Object> features) {
    if (features == null || features.isEmpty()) {
      return FeatureSet.NONE;
    }
    // Map equality and hashing are order-independent and type-aware (Integer 1 is not Long 1), and
    // the same configuration map is usually passed every time, which equals() checks first.
    FeatureSet featureSet = this.featureSets.get(features);
    if (featureSet == null) {
      featureSet = new FeatureSet(features);
      this.featureSets.insert(featureSet);
    }
    return featureSet;
  }

  /**
   * The canonical instance of a distinct feature set in the window: buckets keep its map, so two
   * buckets have the same features exactly when they hold the same map instance.
   */
  static final class FeatureSet extends FlatHashtable.D1.Entry<Map<String, Object>> {
    static final FeatureSet NONE = new FeatureSet(Collections.<String, Object>emptyMap());

    final Map<String, Object> features;

    FeatureSet(final Map<String, Object> features) {
      super(features);
      this.features = features;
    }
  }

  /** The number of full-tier buckets of a flag in the window. */
  static final class FlagCount extends FlatHashtable.D1.Entry<String> {
    int count;

    FlagCount(final String flagKey, final int count) {
      super(flagKey);
      this.count = count;
    }
  }

  /** The key of the event being aggregated, compared with the buckets in place. */
  // Package-private for FlagEvaluationAggregatorTiersTest.
  static final class Lookup {
    FlagEvalEvent event;
    Map<String, Object> context;
    FeatureSet featureSet;
    private long fullHash;
    private long degradedHash;
    private boolean degradedHashed;

    Lookup of(
        final FlagEvalEvent event,
        final Map<String, Object> context,
        final long contextHash,
        final FeatureSet featureSet) {
      this.event = event;
      this.context = context;
      this.featureSet = featureSet;
      this.fullHash =
          fullHash(
              event.flagKey,
              event.variant,
              event.allocationKey,
              event.errorMessage,
              event.targetingKey,
              contextHash,
              event.observeFullEvaluationData,
              featureSet.hash);
      this.degradedHashed = false;
      return this;
    }

    long fullTierHash() {
      return fullHash;
    }

    /** Hashed on demand: most events stay in the full tier. */
    long degradedTierHash() {
      if (!degradedHashed) {
        degradedHash =
            degradedHash(
                event.flagKey,
                event.variant,
                event.allocationKey,
                event.errorMessage,
                featureSet.hash);
        degradedHashed = true;
      }
      return degradedHash;
    }
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
   * A full-tier bucket: every dimension, including the subject, its context and the consent. A null
   * variant, the runtime default, is a dimension through the variant.
   */
  static final class FullTier implements FlatHashtable.MatchingStrategy<EvalBucket, Lookup> {
    @Override
    public long hashKey(final Lookup lookup) {
      return lookup.fullTierHash();
    }

    @Override
    public boolean matches(final EvalBucket bucket, final Lookup lookup) {
      final FlagEvalEvent event = lookup.event;
      return bucket.hash == lookup.fullTierHash()
          && bucket.observeFullEvaluationData == event.observeFullEvaluationData
          && Objects.equals(bucket.flagKey, event.flagKey)
          && Objects.equals(bucket.variant, event.variant)
          && Objects.equals(bucket.allocationKey, event.allocationKey)
          && Objects.equals(bucket.errorMessage, event.errorMessage)
          && Objects.equals(bucket.targetingKey, event.targetingKey)
          && bucket.features == lookup.featureSet.features
          && sameContext(bucket.prunedAttrs, lookup.context);
    }
  }

  /**
   * A degraded-tier bucket, without the subject and its context. Unlike the full tier, consent is
   * NOT a bucket dimension here: the wire serializer for degraded rows (FlagEvaluationPayloads
   * .FlagEvaluationEvent.fromBucket with isFullTier=false) drops the targeting key and context
   * unconditionally, so two degraded buckets differing only in consent would emit byte-identical
   * JSON with evaluation_count split — halving effective DEGRADED_CAP for zero wire fidelity.
   * Mixed-consent events merge into one bucket; the AND-fold on
   * EvalBucket.observeFullEvaluationData still runs but has no downstream effect for degraded rows.
   */
  static final class DegradedTier implements FlatHashtable.MatchingStrategy<EvalBucket, Lookup> {
    @Override
    public long hashKey(final Lookup lookup) {
      return lookup.degradedTierHash();
    }

    @Override
    public boolean matches(final EvalBucket bucket, final Lookup lookup) {
      final FlagEvalEvent event = lookup.event;
      return bucket.hash == lookup.degradedTierHash()
          && Objects.equals(bucket.flagKey, event.flagKey)
          && Objects.equals(bucket.variant, event.variant)
          && Objects.equals(bucket.allocationKey, event.allocationKey)
          && Objects.equals(bucket.errorMessage, event.errorMessage)
          && bucket.features == lookup.featureSet.features;
    }
  }

  /** The buckets of a table, for the flush. */
  static final class Buckets implements Iterator<EvalBucket> {
    private final EvalBucket[] table;
    private int next;

    Buckets(final EvalBucket[] table) {
      this.table = table;
      advance();
    }

    private void advance() {
      while (next < table.length && table[next] == null) {
        next++;
      }
    }

    @Override
    public boolean hasNext() {
      return next < table.length;
    }

    @Override
    public EvalBucket next() {
      if (next >= table.length) {
        throw new NoSuchElementException();
      }
      final EvalBucket bucket = table[next++];
      advance();
      return bucket;
    }
  }

  AggregatedState snapshot() {
    return new AggregatedState(
        list(fullBuckets()), list(degradedBuckets()), droppedDegradedOverflow.get());
  }

  private static List<EvalBucket> list(final Iterable<EvalBucket> buckets) {
    final List<EvalBucket> list = new ArrayList<>();
    for (final EvalBucket bucket : buckets) {
      list.add(bucket);
    }
    return list;
  }

  void simulateFullTierAtCap() {
    for (int i = fullTierSize; i < GLOBAL_CAP; i++) {
      final String key = "synthetic-full-" + i;
      insertFull(
          new EvalBucket(
              fullHash(key, "on", "alloc", null, null, 0L, false, FeatureSet.NONE.hash),
              key,
              "on",
              "alloc",
              null,
              null,
              1L,
              false,
              null,
              false,
              FeatureSet.NONE.features));
      final FlagCount flagCount = perFlagCount.get(key);
      if (flagCount == null) {
        perFlagCount.insert(new FlagCount(key, 1));
      } else {
        flagCount.count++;
      }
    }
  }

  void simulateDegradedTierAtCap() {
    for (int i = degradedTierSize; i < DEGRADED_CAP; i++) {
      final String key = "synthetic-dg-" + i;
      addDegradedBucketForTest(key, "on", "alloc", null, 1L);
    }
  }

  void addDegradedBucketForTest(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String errorMessage,
      final long evalTimeMs) {
    insertDegraded(
        new EvalBucket(
            degradedHash(flagKey, variant, allocationKey, errorMessage, FeatureSet.NONE.hash),
            flagKey,
            variant,
            allocationKey,
            null,
            errorMessage,
            evalTimeMs,
            variant == null,
            null,
            false,
            FeatureSet.NONE.features));
  }

  /** Sets the number of full-tier buckets of a flag, for tests. */
  void setFlagCountForTest(final String flagKey, final int count) {
    final FlagCount flagCount = perFlagCount.get(flagKey);
    if (flagCount == null) {
      perFlagCount.insert(new FlagCount(flagKey, count));
    } else {
      flagCount.count = count;
    }
  }

  /**
   * Hashes a context the way {@link #sameContext} compares it: independent of iteration order, and
   * distinguishing value types (Integer 1, Long 1 and Double 1.0 hash and compare apart).
   *
   * <p>Entries are combined by addition, so the order the map yields them in does not matter; each
   * entry is mixed first, so that swapping values between keys changes the hash.
   */
  static long contextHash(final Map<String, Object> context) {
    if (context == null || context.isEmpty()) {
      return 0L;
    }
    long hash = context.size();
    for (final Map.Entry<String, Object> entry : context.entrySet()) {
      final Object value = entry.getValue();
      final long valueHash =
          value == null ? 0L : (((long) value.getClass().hashCode()) << 32) ^ value.hashCode();
      hash += mix(entry.getKey().hashCode() * 0x9E3779B97F4A7C15L + valueHash);
    }
    return hash;
  }

  /** The finalizer of SplitMix64: spreads every input bit over the whole result. */
  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /**
   * Whether two contexts would be emitted the same. A missing context and an empty one are the
   * same, and values compare with {@code equals}, which already tells value types apart.
   */
  static boolean sameContext(final Map<String, Object> a, final Map<String, Object> b) {
    if (a == b) {
      return true;
    }
    if (a == null || a.isEmpty()) {
      return b == null || b.isEmpty();
    }
    return a.equals(b);
  }

  /**
   * A bucket, and its own entry in its tier's table: it carries its key and the key's hash, so a
   * bucket is one object.
   */
  static class EvalBucket extends FlatHashtable.Entry {
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
    // Identical for every evaluation in the bucket, because the features key is a bucket dimension.
    Map<String, Object> features;
    // Consent to emit raw PII. For full-tier buckets this is fixed at creation (consent is a key
    // dimension), so only degraded merges change it. For degraded-tier buckets consent is NOT
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
        final boolean observeFullEvaluationData,
        final Map<String, Object> features) {
      this(
          0,
          flagKey,
          variant,
          allocationKey,
          targetingKey,
          errorMessage,
          evalTimeMs,
          runtimeDefaultUsed,
          prunedAttrs,
          observeFullEvaluationData,
          features);
    }

    EvalBucket(
        final long hash,
        final String flagKey,
        final String variant,
        final String allocationKey,
        final String targetingKey,
        final String errorMessage,
        final long evalTimeMs,
        final boolean runtimeDefaultUsed,
        final Map<String, Object> prunedAttrs,
        final boolean observeFullEvaluationData,
        final Map<String, Object> features) {
      super(hash);
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
      this.features = features;
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

  static class AggregatedState {
    final List<EvalBucket> fullTier;
    final List<EvalBucket> degradedTier;
    final long droppedDegradedOverflow;

    AggregatedState(
        final List<EvalBucket> fullTier,
        final List<EvalBucket> degradedTier,
        final long droppedDegradedOverflow) {
      this.fullTier = fullTier;
      this.degradedTier = degradedTier;
      this.droppedDegradedOverflow = droppedDegradedOverflow;
    }
  }
}

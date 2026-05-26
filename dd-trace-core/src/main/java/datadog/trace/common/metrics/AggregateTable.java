package datadog.trace.common.metrics;

import datadog.trace.util.Hashtable;
import datadog.trace.util.Hashtable.MutatingTableIterator;
import datadog.trace.util.Hashtable.Support;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Consumer-side {@link AggregateEntry} store, keyed on the raw fields of a {@link SpanSnapshot}.
 *
 * <p>Replaces the prior {@code LRUCache<MetricKey, AggregateMetric>}. The win is on the
 * steady-state hit path: a snapshot lookup is a 64-bit hash compute + bucket walk + field-wise
 * {@code matches}, with no per-snapshot {@link AggregateEntry} allocation and no UTF8 cache
 * lookups. The UTF8-encoded forms (formerly held on {@code MetricKey}) and the mutable counters
 * (formerly held on {@code AggregateMetric}) both live on the {@link AggregateEntry} now, built
 * once per unique key at insert time.
 *
 * <p><b>Not thread-safe.</b> The aggregator thread is the sole writer of both this table and its
 * contained {@link AggregateEntry} state. Any cross-thread request that needs to mutate -- e.g.
 * {@link ConflatingMetricsAggregator#disable()} -- must funnel onto the aggregator thread via the
 * inbox (see the {@code ClearSignal} routing in {@link Aggregator}). The invariant is convention-
 * enforced; nothing here checks the calling thread at runtime, so a wrong-thread call would corrupt
 * bucket chains silently.
 */
final class AggregateTable {

  private final Hashtable.Entry[] buckets;
  private final int maxAggregates;
  private int size;

  /**
   * Bucket index where the last {@link #evictOneStale} successfully removed an entry. The next call
   * resumes from this bucket so a fast-evicting workload doesn't repeatedly re-walk the same hot
   * entries clustered near bucket 0. Reset to {@code 0} by {@link #clear}.
   */
  private int evictCursor;

  AggregateTable(int maxAggregates) {
    this.buckets = Support.create(maxAggregates, Support.MAX_RATIO);
    this.maxAggregates = maxAggregates;
  }

  int size() {
    return size;
  }

  boolean isEmpty() {
    return size == 0;
  }

  /**
   * Returns the {@link AggregateEntry} to update for {@code snapshot}, lazily creating one on miss.
   * Returns {@code null} when the table is at capacity and no stale entry can be evicted -- the
   * caller should drop the data point in that case.
   */
  AggregateEntry findOrInsert(SpanSnapshot snapshot) {
    long keyHash = AggregateEntry.hashOf(snapshot);
    for (AggregateEntry candidate = Support.bucket(buckets, keyHash);
        candidate != null;
        candidate = candidate.next()) {
      if (candidate.matches(keyHash, snapshot)) {
        return candidate;
      }
    }
    if (size >= maxAggregates && !evictOneStale()) {
      return null;
    }
    AggregateEntry entry = AggregateEntry.forSnapshot(snapshot, keyHash);
    Support.insertHeadEntry(buckets, keyHash, entry);
    size++;
    return entry;
  }

  /**
   * Unlinks the first entry whose {@code getHitCount() == 0}, resuming the scan from {@link
   * #evictCursor} so back-to-back evictions amortize to O(1) per call. Worst case for a single call
   * is still O(N) when nearly every entry is hot, but a sustained eviction stream never re-scans
   * the hot prefix more than twice across N evictions.
   *
   * <p>The semantic intent: at cap with all entries live, drop the new key (reported via {@code
   * onStatsAggregateDropped}) rather than evicting an established one. Cap is sized to the
   * steady-state working set, so eviction is rare; this cursor optimization handles the
   * pathological "persistently at cap" case.
   */
  private boolean evictOneStale() {
    // Two passes -- [cursor, length) then [0, cursor) -- using the half-open-range iterator. The
    // second pass is naturally empty when cursor==0, so no extra check needed.
    return evictOneStaleInRange(evictCursor, buckets.length)
        || evictOneStaleInRange(0, evictCursor);
  }

  /** Scans {@code [startBucket, endBucket)} for the first stale entry and unlinks it. */
  private boolean evictOneStaleInRange(int startBucket, int endBucket) {
    MutatingTableIterator<AggregateEntry> iter =
        Support.mutatingTableIterator(buckets, startBucket, endBucket);
    while (iter.hasNext()) {
      AggregateEntry e = iter.next();
      if (e.getHitCount() == 0) {
        int bucket = iter.currentBucket();
        iter.remove();
        size--;
        evictCursor = bucket;
        return true;
      }
    }
    return false;
  }

  void forEach(Consumer<AggregateEntry> consumer) {
    Support.forEach(buckets, consumer);
  }

  /**
   * Context-passing forEach. Useful for callers that want to avoid a capturing-lambda allocation on
   * each invocation -- pass a non-capturing {@link BiConsumer} (typically a {@code static final})
   * plus whatever side-band state it needs as {@code context}.
   */
  <T> void forEach(T context, BiConsumer<T, AggregateEntry> consumer) {
    Support.forEach(buckets, context, consumer);
  }

  /** Removes entries whose {@code getHitCount() == 0}. */
  void expungeStaleAggregates() {
    for (MutatingTableIterator<AggregateEntry> iter = Support.mutatingTableIterator(buckets);
        iter.hasNext(); ) {
      AggregateEntry e = iter.next();
      if (e.getHitCount() == 0) {
        iter.remove();
        size--;
      }
    }
  }

  void clear() {
    Support.clear(buckets);
    size = 0;
    evictCursor = 0;
  }
}

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
 * <p><b>Not thread-safe.</b> The aggregator thread is the sole writer; {@link #clear()} must be
 * routed through the inbox rather than called from arbitrary threads.
 */
final class AggregateTable {

  private final Hashtable.Entry[] buckets;
  private final int maxAggregates;
  private int size;

  AggregateTable(int maxAggregates) {
    // ~25% headroom in the bucket array over the working-set target -- avoids the long-chain
    // pathology at full capacity.
    this.buckets =
        Support.create(maxAggregates * Support.MAX_RATIO_NUMERATOR / Support.MAX_RATIO_DENOMINATOR);
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
      if (candidate.keyHash == keyHash && candidate.matches(snapshot)) {
        return candidate;
      }
    }
    if (size >= maxAggregates && !evictOneStale()) {
      return null;
    }
    AggregateEntry entry = AggregateEntry.forSnapshot(snapshot);
    Support.insertHeadEntry(buckets, Support.bucketIndex(buckets, keyHash), entry);
    size++;
    return entry;
  }

  /** Unlink the first entry whose {@code getHitCount() == 0}. */
  private boolean evictOneStale() {
    for (MutatingTableIterator<AggregateEntry> it = Support.mutatingTableIterator(buckets);
        it.hasNext(); ) {
      AggregateEntry e = it.next();
      if (e.getHitCount() == 0) {
        it.remove();
        size--;
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
    for (MutatingTableIterator<AggregateEntry> it = Support.mutatingTableIterator(buckets);
        it.hasNext(); ) {
      AggregateEntry e = it.next();
      if (e.getHitCount() == 0) {
        it.remove();
        size--;
      }
    }
  }

  void clear() {
    Support.clear(buckets);
    size = 0;
  }
}

package datadog.trace.common.metrics;

import datadog.trace.util.Hashtable;
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
    this.buckets = Hashtable.Support.create(maxAggregates * 4 / 3);
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
    for (AggregateEntry candidate = Hashtable.Support.bucket(buckets, keyHash);
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
    int bucketIndex = Hashtable.Support.bucketIndex(buckets, keyHash);
    entry.setNext(buckets[bucketIndex]);
    buckets[bucketIndex] = entry;
    size++;
    return entry;
  }

  /** Unlink the first entry whose {@code getHitCount() == 0}. */
  private boolean evictOneStale() {
    for (int i = 0; i < buckets.length; i++) {
      AggregateEntry head = (AggregateEntry) buckets[i];
      if (head == null) {
        continue;
      }
      if (head.getHitCount() == 0) {
        buckets[i] = head.next();
        size--;
        return true;
      }
      AggregateEntry prev = head;
      AggregateEntry cur = head.next();
      while (cur != null) {
        if (cur.getHitCount() == 0) {
          prev.setNext(cur.next());
          size--;
          return true;
        }
        prev = cur;
        cur = cur.next();
      }
    }
    return false;
  }

  void forEach(Consumer<AggregateEntry> consumer) {
    Hashtable.Support.forEach(buckets, consumer);
  }

  /**
   * Context-passing forEach. Useful for callers that want to avoid a capturing-lambda allocation on
   * each invocation -- pass a non-capturing {@link BiConsumer} (typically a {@code static final})
   * plus whatever side-band state it needs as {@code context}.
   */
  <T> void forEach(T context, BiConsumer<T, AggregateEntry> consumer) {
    Hashtable.Support.forEach(buckets, context, consumer);
  }

  /** Removes entries whose {@code getHitCount() == 0}. */
  void expungeStaleAggregates() {
    for (int i = 0; i < buckets.length; i++) {
      // unlink leading stale entries
      AggregateEntry head = (AggregateEntry) buckets[i];
      while (head != null && head.getHitCount() == 0) {
        head = head.next();
        size--;
      }
      buckets[i] = head;
      if (head == null) {
        continue;
      }
      // unlink stale entries in the chain
      AggregateEntry prev = head;
      AggregateEntry cur = head.next();
      while (cur != null) {
        if (cur.getHitCount() == 0) {
          AggregateEntry skipped = cur.next();
          prev.setNext(skipped);
          size--;
          cur = skipped;
        } else {
          prev = cur;
          cur = cur.next();
        }
      }
    }
  }

  void clear() {
    Hashtable.Support.clear(buckets);
    size = 0;
  }
}

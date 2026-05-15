package datadog.trace.common.metrics;

import datadog.trace.util.Hashtable;
import java.util.function.Consumer;

/**
 * Consumer-side {@link AggregateMetric} store, keyed on the raw fields of a {@link SpanSnapshot}.
 *
 * <p>Replaces the prior {@code LRUCache<MetricKey, AggregateMetric>}. The win is on the
 * steady-state hit path: a snapshot lookup is a 64-bit hash compute + bucket walk + field-wise
 * {@code matches}, with no per-snapshot {@link AggregateEntry} allocation and no UTF8 cache
 * lookups. The UTF8-encoded forms (formerly held on {@code MetricKey}) live on the {@link
 * AggregateEntry} itself and are built once per unique key at insert time.
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
   * Returns the {@link AggregateMetric} to update for {@code snapshot}, lazily creating an entry on
   * miss. Returns {@code null} when the table is at capacity and no stale entry can be evicted --
   * the caller should drop the data point in that case.
   */
  AggregateMetric findOrInsert(SpanSnapshot snapshot) {
    long keyHash = AggregateEntry.hashOf(snapshot);
    int bucketIndex = Hashtable.Support.bucketIndex(buckets, keyHash);
    for (Hashtable.Entry e = buckets[bucketIndex]; e != null; e = e.next()) {
      if (e.keyHash == keyHash) {
        AggregateEntry candidate = (AggregateEntry) e;
        if (candidate.matches(snapshot)) {
          return candidate.aggregate;
        }
      }
    }
    if (size >= maxAggregates && !evictOneStale()) {
      return null;
    }
    AggregateEntry entry = AggregateEntry.forSnapshot(snapshot, new AggregateMetric());
    entry.setNext(buckets[bucketIndex]);
    buckets[bucketIndex] = entry;
    size++;
    return entry.aggregate;
  }

  /** Unlink the first entry whose {@code AggregateMetric.getHitCount() == 0}. */
  private boolean evictOneStale() {
    for (int i = 0; i < buckets.length; i++) {
      Hashtable.Entry head = buckets[i];
      if (head == null) {
        continue;
      }
      if (((AggregateEntry) head).aggregate.getHitCount() == 0) {
        buckets[i] = head.next();
        size--;
        return true;
      }
      Hashtable.Entry prev = head;
      Hashtable.Entry cur = head.next();
      while (cur != null) {
        if (((AggregateEntry) cur).aggregate.getHitCount() == 0) {
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
    for (int i = 0; i < buckets.length; i++) {
      for (Hashtable.Entry e = buckets[i]; e != null; e = e.next()) {
        consumer.accept((AggregateEntry) e);
      }
    }
  }

  /** Removes entries whose {@code AggregateMetric.getHitCount() == 0}. */
  void expungeStaleAggregates() {
    for (int i = 0; i < buckets.length; i++) {
      // unlink leading stale entries
      Hashtable.Entry head = buckets[i];
      while (head != null && ((AggregateEntry) head).aggregate.getHitCount() == 0) {
        head = head.next();
        size--;
      }
      buckets[i] = head;
      if (head == null) {
        continue;
      }
      // unlink stale entries in the chain
      Hashtable.Entry prev = head;
      Hashtable.Entry cur = head.next();
      while (cur != null) {
        if (((AggregateEntry) cur).aggregate.getHitCount() == 0) {
          Hashtable.Entry skipped = cur.next();
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

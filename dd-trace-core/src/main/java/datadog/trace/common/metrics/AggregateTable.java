package datadog.trace.common.metrics;

import datadog.trace.util.Hashtable;
import java.util.function.Consumer;

/**
 * Consumer-side {@link AggregateMetric} store, keyed on the canonical UTF8-encoded labels of a
 * {@link SpanSnapshot}.
 *
 * <p>{@link #findOrInsert} canonicalizes the snapshot's fields through the cardinality handlers (so
 * cardinality-blocked values share a sentinel and collapse into one entry) and then computes the
 * lookup hash from that canonical form. Canonicalization runs into a reusable {@link
 * AggregateEntry.Canonical} scratch buffer; on a hit nothing is allocated, on a miss the buffer's
 * references are copied into a fresh entry and the buffer is overwritten on the next call.
 *
 * <p><b>Not thread-safe.</b> The aggregator thread is the sole writer; {@link #clear()} must be
 * routed through the inbox rather than called from arbitrary threads.
 */
final class AggregateTable {

  private final Hashtable.Entry[] buckets;
  private final int maxAggregates;
  private final AggregateEntry.Canonical canonical = new AggregateEntry.Canonical();
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
    canonical.populate(snapshot);
    long keyHash = canonical.keyHash;
    int bucketIndex = Hashtable.Support.bucketIndex(buckets, keyHash);
    for (Hashtable.Entry e = buckets[bucketIndex]; e != null; e = e.next()) {
      if (e.keyHash == keyHash) {
        AggregateEntry candidate = (AggregateEntry) e;
        if (canonical.matches(candidate)) {
          return candidate.aggregate;
        }
      }
    }
    if (size >= maxAggregates && !evictOneStale()) {
      return null;
    }
    AggregateEntry entry = canonical.toEntry(new AggregateMetric());
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

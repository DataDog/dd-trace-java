package datadog.trace.common.metrics;

import datadog.trace.util.Hashtable;
import datadog.trace.util.Hashtable.MutatingTableIterator;
import datadog.trace.util.Hashtable.Support;
import java.util.function.BiConsumer;
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
    // ~25% headroom in the bucket array over the working-set target -- avoids the long-chain
    // pathology at full capacity.
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
   * Returns the {@link AggregateMetric} to update for {@code snapshot}, lazily creating an entry on
   * miss. Returns {@code null} when the table is at capacity and no stale entry can be evicted --
   * the caller should drop the data point in that case.
   */
  AggregateMetric findOrInsert(SpanSnapshot snapshot) {
    canonical.populate(snapshot);
    long keyHash = canonical.keyHash;
    for (AggregateEntry candidate = Support.bucket(buckets, keyHash);
        candidate != null;
        candidate = candidate.next()) {
      if (candidate.keyHash == keyHash && canonical.matches(candidate)) {
        return candidate.aggregate;
      }
    }
    if (size >= maxAggregates && !evictOneStale()) {
      return null;
    }
    AggregateEntry entry = canonical.toEntry(new AggregateMetric());
    Support.insertHeadEntry(buckets, keyHash, entry);
    size++;
    return entry.aggregate;
  }

  /** Unlink the first entry whose {@code AggregateMetric.getHitCount() == 0}. */
  private boolean evictOneStale() {
    for (MutatingTableIterator<AggregateEntry> iter = Support.mutatingTableIterator(buckets);
        iter.hasNext(); ) {
      AggregateEntry e = iter.next();
      if (e.aggregate.getHitCount() == 0) {
        iter.remove();
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

  /** Removes entries whose {@code AggregateMetric.getHitCount() == 0}. */
  void expungeStaleAggregates() {
    for (MutatingTableIterator<AggregateEntry> iter = Support.mutatingTableIterator(buckets);
        iter.hasNext(); ) {
      AggregateEntry e = iter.next();
      if (e.aggregate.getHitCount() == 0) {
        iter.remove();
        size--;
      }
    }
  }

  void clear() {
    Support.clear(buckets);
    size = 0;
  }
}

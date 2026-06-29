package datadog.trace.common.metrics;

import datadog.trace.util.Hashtable;
import datadog.trace.util.Hashtable.MutatingTableIterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The {@link AggregateEntry} store of the consuming aggregator thread, keyed on the canonical
 * UTF8-encoded labels of a {@link SpanSnapshot}.
 *
 * <p>{@link #findOrInsert} canonicalizes the snapshot's fields through the cardinality handlers (so
 * cardinality-blocked values share a sentinel and collapse into one entry) and then computes the
 * lookup hash from that canonical form. Canonicalization runs into a reusable {@link
 * AggregateEntry.Canonical} scratch buffer; on a hit nothing is allocated, on a miss the buffer's
 * references are copied into a fresh entry and the buffer is overwritten on the next call.
 *
 * <p><b>Not thread-safe.</b> The aggregator thread is the sole writer of both this table and its
 * contained {@link AggregateEntry} state. Any cross-thread request that needs to mutate -- e.g.
 * {@link ClientStatsAggregator#disable()} -- must funnel onto the aggregator thread via the inbox
 * (see the {@code ClearSignal} routing in {@link Aggregator}). The invariant is convention-
 * enforced; nothing here checks the calling thread at runtime, so a wrong-thread call would corrupt
 * bucket chains silently.
 */
final class AggregateTable {

  private final Hashtable.Entry[] buckets;
  private final int maxAggregates;
  private final AggregateEntry.Canonical canonical = new AggregateEntry.Canonical();
  private int size;

  /**
   * Bucket index where the last {@link #evictOneStale} successfully removed an entry. The next call
   * resumes from this bucket so a fast-evicting workload doesn't repeatedly re-walk the same hot
   * entries clustered near bucket 0. Reset to {@code 0} by {@link #clear}.
   */
  private int evictCursor;

  AggregateTable(int maxAggregates) {
    this.buckets = Hashtable.Support.create(maxAggregates, Hashtable.Support.MAX_RATIO);
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
    canonical.populateFrom(snapshot);
    long keyHash = canonical.keyHash;
    for (AggregateEntry candidate = Hashtable.Support.bucket(buckets, keyHash);
        candidate != null;
        candidate = candidate.next()) {
      if (candidate.keyHash == keyHash && canonical.matches(candidate)) {
        return candidate;
      }
    }
    if (size >= maxAggregates && !evictOneStale()) {
      return null;
    }
    AggregateEntry entry = canonical.createEntry();
    Hashtable.Support.insertHeadEntry(buckets, keyHash, entry);
    size++;
    return entry;
  }

  /**
   * Unlinks the first entry whose {@code getHitCount() == 0}, resuming the scan from {@link
   * #evictCursor} so consecutive evictions amortize to O(1) per call. Worst case for a single call
   * is still O(N) when nearly every entry is hot, but a sustained eviction stream never re-scans
   * the hot prefix more than twice across N evictions.
   *
   * <p>If the table is full and every entry was used in this cycle, drop the new key (reported via
   * {@code onStatsAggregateDropped}) rather than evicting an established one. Cap is sized to the
   * steady-state working set, so eviction is rare in the common case.
   *
   * <p>How often this fires depends on {@link AggregateEntry#LIMITS_ENABLED}. With limits enabled,
   * over-cap values for a given field collapse into a shared {@code blocked_by_tracer} bucket, so
   * the table itself rarely reaches {@code maxAggregates}. With limits disabled (the default),
   * over-cap values flow to distinct buckets and {@code maxAggregates} becomes the load-bearing
   * backstop -- the cursor-resumed scan was added specifically for this regime.
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
        Hashtable.Support.mutatingTableIterator(buckets, startBucket, endBucket);
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
    for (MutatingTableIterator<AggregateEntry> iter =
            Hashtable.Support.mutatingTableIterator(buckets);
        iter.hasNext(); ) {
      AggregateEntry e = iter.next();
      if (e.getHitCount() == 0) {
        iter.remove();
        size--;
      }
    }
  }

  void clear() {
    Hashtable.Support.clear(buckets);
    size = 0;
    evictCursor = 0;
  }
}

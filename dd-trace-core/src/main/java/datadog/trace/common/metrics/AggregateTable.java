package datadog.trace.common.metrics;

import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.Hashtable;
import java.util.function.Consumer;

/**
 * Consumer-side {@link AggregateEntry} store, keyed on the canonical UTF8-encoded labels of a
 * {@link SpanSnapshot}.
 *
 * <p>{@link #findOrInsert} canonicalizes the snapshot's fields through the cardinality handlers (so
 * cardinality-blocked values share a sentinel and collapse into one entry) and then computes the
 * lookup hash from that canonical form. Canonicalization runs into a reusable {@link
 * AggregateEntry.Canonical} scratch buffer; on a hit nothing is allocated, on a miss the buffer's
 * references are copied into a fresh entry and the buffer is overwritten on the next call.
 *
 * <p>Additional metric tags get a second layer of cardinality protection: brand-new entries that
 * would push the bucket past {@link AdditionalTagsCardinalityLimiter#isAtCap()} have all their
 * present additional-tag slots replaced by the schema's blocked sentinels before the bucket
 * lookup. Spans whose canonical (including the additional tags) is already in the table merge
 * normally regardless of the cap.
 *
 * <p><b>Not thread-safe.</b> The aggregator thread is the sole writer; {@link #clear()} must be
 * routed through the inbox rather than called from arbitrary threads.
 */
final class AggregateTable {

  private final Hashtable.Entry[] buckets;
  private final int maxAggregates;
  private final AdditionalTagsCardinalityLimiter additionalTagsLimiter;
  private final AggregateEntry.Canonical canonical;
  private int size;

  AggregateTable(int maxAggregates) {
    this(
        maxAggregates,
        AdditionalTagsSchema.EMPTY,
        new AdditionalTagsCardinalityLimiter(100, HealthMetrics.NO_OP),
        HealthMetrics.NO_OP);
  }

  AggregateTable(
      int maxAggregates,
      AdditionalTagsSchema additionalTagsSchema,
      AdditionalTagsCardinalityLimiter additionalTagsLimiter,
      HealthMetrics healthMetrics) {
    this.buckets = Hashtable.Support.create(maxAggregates * 4 / 3);
    this.maxAggregates = maxAggregates;
    this.additionalTagsLimiter = additionalTagsLimiter;
    this.canonical = new AggregateEntry.Canonical(additionalTagsSchema, additionalTagsLimiter);
  }

  int size() {
    return size;
  }

  boolean isEmpty() {
    return size == 0;
  }

  /**
   * Returns the {@link AggregateEntry} to update for {@code snapshot}, lazily creating it on miss.
   * Returns {@code null} when the table is at capacity and no stale entry can be evicted -- the
   * caller should drop the data point in that case.
   */
  AggregateEntry findOrInsert(SpanSnapshot snapshot) {
    canonical.populate(snapshot);
    long keyHash = canonical.keyHash;
    int bucketIndex = Hashtable.Support.bucketIndex(buckets, keyHash);
    for (Hashtable.Entry e = buckets[bucketIndex]; e != null; e = e.next()) {
      if (e.keyHash == keyHash) {
        AggregateEntry candidate = (AggregateEntry) e;
        if (canonical.matches(candidate)) {
          return candidate;
        }
      }
    }
    // Miss path. If this brand-new entry has any additional-tag values and the bucket cap is
    // reached, mask every present slot with the per-key blocked sentinel, recompute the hash, and
    // re-resolve the bucket -- so blocked entries collapse into a small number of shape buckets
    // rather than the no-additional-tags base bucket.
    boolean countedTowardAdditionalTagBudget = false;
    if (canonical.hasAdditionalTags()) {
      if (additionalTagsLimiter.isAtCap()) {
        additionalTagsLimiter.recordCardinalityBlock(
            canonical.additionalTagsSchema, snapshot.additionalTagValues);
        canonical.rebuildAdditionalTagsWithBlockedSentinels();
        keyHash = canonical.keyHash;
        bucketIndex = Hashtable.Support.bucketIndex(buckets, keyHash);
        // Re-scan: the masked canonical may already match an existing "all-blocked" entry.
        for (Hashtable.Entry e = buckets[bucketIndex]; e != null; e = e.next()) {
          if (e.keyHash == keyHash) {
            AggregateEntry candidate = (AggregateEntry) e;
            if (canonical.matches(candidate)) {
              return candidate;
            }
          }
        }
      } else {
        countedTowardAdditionalTagBudget = true;
      }
    }
    if (size >= maxAggregates && !evictOneStale()) {
      return null;
    }
    AggregateEntry entry = canonical.toEntry();
    entry.setNext(buckets[bucketIndex]);
    buckets[bucketIndex] = entry;
    size++;
    if (countedTowardAdditionalTagBudget) {
      additionalTagsLimiter.onNewStatEntryAdmitted();
    }
    return entry;
  }

  /** Unlink the first entry whose {@code getHitCount() == 0}. */
  private boolean evictOneStale() {
    for (int i = 0; i < buckets.length; i++) {
      Hashtable.Entry head = buckets[i];
      if (head == null) {
        continue;
      }
      if (((AggregateEntry) head).getHitCount() == 0) {
        buckets[i] = head.next();
        size--;
        return true;
      }
      Hashtable.Entry prev = head;
      Hashtable.Entry cur = head.next();
      while (cur != null) {
        if (((AggregateEntry) cur).getHitCount() == 0) {
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

  /** Removes entries whose {@code getHitCount() == 0}. */
  void expungeStaleAggregates() {
    for (int i = 0; i < buckets.length; i++) {
      // unlink leading stale entries
      Hashtable.Entry head = buckets[i];
      while (head != null && ((AggregateEntry) head).getHitCount() == 0) {
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
        if (((AggregateEntry) cur).getHitCount() == 0) {
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

package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.EncodingCache;
import java.nio.charset.StandardCharsets;

/**
 * A simple UTF8 cache - primarily intended for tag names
 *
 * <p>Cache is designed to be resilient against single use values
 *
 * <p>NOTE: The aim of this cache is to reduce allocation overhead -- not CPU overhead. Using the
 * cache has higher CPU overhead than simply calling {@link
 * String#getBytes(java.nio.charset.Charset)}.
 *
 * <p>The cache is thread safe.
 */
/*
 * Thread safety is achieved through using CacheEntry objects where the key data
 * fields are final.
 *
 * Updating of the cache and bookkeeping are deliberately allowed to be racy to
 * minimize CPU overhead and lock contention.
 *
 * The first time a value is requested, the value isn't cached and no CacheEntry
 * is created.  Without this refinement, the cost for constructing
 * CacheEntry for unique values would negate the benefit of the cache.
 *
 * These first requests are tracked via markers which indicate if there was
 * previously an unsatisfied request to the same initial cache line.
 *
 * If there was a request, then CacheEntry is created and stored into entries.
 * NOTE: The cache line marking process is imprecise and subject to request
 * ordering issues. But given that low cardinality entries are more likely to repeat
 * next, imperically this scheme works well.
 *
 * If a collision occurs in the cache, linear probing is used to check other slots.
 * New cache entries fill any available slot within the probing window.
 *
 * If a subsequent request, finds a matching item in entries.  The hit count
 * of the CacheEntry is bumped.
 *
 * If there are no available slots in entries for a newly created CacheEntry,
 * a LFU: least frequently used eviction policy is used to free up a slot.
 */
public final class SimpleUtf8Cache implements EncodingCache {
  static final int MAX_CAPACITY = 1024;

  private static final int MAX_PROBES = 4;

  private final int[] markers;
  private final CacheEntry[] entries;

  private static final double HIT_DECAY = 0.5D;
  private static final double PURGE_THRESHOLD = 0.25D;

  static final int MAX_ENTRY_LEN = 128;

  protected int hits = 0;
  protected int evictions = 0;

  public SimpleUtf8Cache(int capacity) {
    int size = Caching.cacheSizeFor(Math.min(capacity, MAX_CAPACITY));

    this.markers = new int[size];
    this.entries = new CacheEntry[size];
  }

  public int capacity() {
    return this.entries.length;
  }

  /**
   * Recalibrates the cache Applies a decay to existing entries - and purges entries below the
   * PURGE_THRESHOLD
   *
   * <p>While still racy this method is synchronized to avoid simultaneous recalibrations
   */
  public synchronized void recalibrate() {
    CacheEntry[] thisEntries = this.entries;
    for (int i = 0; i < thisEntries.length; ++i) {
      CacheEntry entry = thisEntries[i];
      if (entry == null) continue;

      boolean purge = entry.decay();
      if (purge) thisEntries[i] = null;
    }

    Caching.reset(this.markers);
  }

  @Override
  public byte[] encode(CharSequence charSeq) {
    if (charSeq instanceof String) {
      String str = (String) charSeq;
      return this.getUtf8(str);
    } else {
      return null;
    }
  }

  /** Returns the UTF-8 encoding of value -- using a cache value if available */
  public final byte[] getUtf8(String value) {
    if (value.length() > MAX_ENTRY_LEN) return CacheEntry.utf8(value);

    CacheEntry[] thisEntries = this.entries;

    int adjHash = Caching.adjHash(value);

    CacheEntry matchingEntry = lookupEntry(thisEntries, adjHash, value);
    if (matchingEntry != null) {
      matchingEntry.hit();

      this.hits += 1;
      return matchingEntry.utf8();
    }

    boolean wasMarked = Caching.mark(this.markers, adjHash);
    if (!wasMarked) return CacheEntry.utf8(value);

    CacheEntry newEntry = new CacheEntry(adjHash, value);
    newEntry.hit();

    boolean evicted = lfuInsert(thisEntries, newEntry);
    if (evicted) this.evictions += 1;

    return newEntry.utf8();
  }

  static final CacheEntry lookupEntry(CacheEntry[] entries, int adjHash, String value) {
    int initialBucketIndex = Caching.bucketIndex(entries, adjHash);
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry != null && entry.matches(adjHash, value)) {
        return entry;
      }
    }
    return null;
  }

  static final boolean lfuInsert(CacheEntry[] entries, CacheEntry newEntry) {
    int initialBucketIndex = Caching.bucketIndex(entries, newEntry.adjHash());

    // initial scan to see if there's an empty slot or marker entry is already present
    double lowestHits = Double.MAX_VALUE;
    int lfuIndex = -1;
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null || entry.isPurgeable()) {
        entries[index] = newEntry;
        return false;
      }

      double hits = entry.score();
      if (hits < lowestHits) {
        lowestHits = hits;
        lfuIndex = index;
      }
    }

    // If we get here, then we're evicting the LRU
    entries[lfuIndex] = newEntry;
    return true;
  }

  static final class CacheEntry {
    final int adjHash;
    final String value;
    final byte[] valueUtf8;

    boolean promoted = false;
    double score = 0;

    public CacheEntry(int adjHash, String value) {
      this.adjHash = adjHash;
      this.value = value;
      this.valueUtf8 = utf8(value);
    }

    boolean matches(CacheEntry thatEntry) {
      return (this == thatEntry) || this.matches(thatEntry.adjHash, thatEntry.value);
    }

    boolean matches(int adjHash, String value) {
      return (this.adjHash == adjHash) && value.equals(this.value);
    }

    int adjHash() {
      return this.adjHash;
    }

    double score() {
      return this.score;
    }

    byte[] utf8() {
      return this.valueUtf8;
    }

    double hit() {
      this.score += 1;

      return this.score;
    }

    boolean decay() {
      this.score *= HIT_DECAY;

      return this.isPurgeable();
    }

    boolean isPurgeable() {
      return (this.score < PURGE_THRESHOLD);
    }

    static final byte[] utf8(String value) {
      return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
      return this.value + " - score: " + this.score;
    }
  }
}

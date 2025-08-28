package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.EncodingCache;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A simple UTF8 cache - primarily intended for tag names
 *
 * <p>Cache is designed to against resilient against single use tags
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
 * ordering issues, but given that low cardinality entries are more likely to repeat
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
  public static final SimpleUtf8Cache INSTANCE = new SimpleUtf8Cache();

  private static final int MAX_PROBES = 8;

  private final int SIZE = 256;

  private final boolean[] markers = new boolean[SIZE];
  private final CacheEntry[] entries = new CacheEntry[SIZE];

  private static final double HIT_DECAY = 0.8D;
  private static final double PURGE_THRESHOLD = 0.25D;

  protected int hits = 0;
  protected int evictions = 0;

  public void recalibrate() {
    CacheEntry[] thisEntries = this.entries;
    for (int i = 0; i < thisEntries.length; ++i) {
      CacheEntry entry = thisEntries[i];
      if (entry == null) continue;

      boolean purge = entry.decay();
      if (purge) thisEntries[i] = null;
    }

    Arrays.fill(this.markers, false);
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
    CacheEntry[] thisEntries = this.entries;

    int valueHash = value.hashCode();

    CacheEntry matchingEntry = lookupEntry(thisEntries, valueHash, value);
    if (matchingEntry != null) {
      this.hits += 1;
      return matchingEntry.utf8();
    }

    boolean wasMarked = reverseMark(this.markers, valueHash);
    if (!wasMarked) return CacheEntry.utf8(value);

    CacheEntry newEntry = new CacheEntry(valueHash, value);
    newEntry.hit();

    boolean evicted = lfuInsert(thisEntries, newEntry);
    if (evicted) this.evictions += 1;

    return newEntry.utf8();
  }

  static final CacheEntry lookupEntry(CacheEntry[] entries, int valueHash, String value) {
    int initialBucketIndex = initialBucketIndex(entries, valueHash);
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry != null && entry.matches(valueHash, value)) {
        return entry;
      }
    }
    return null;
  }

  static final boolean lfuInsert(CacheEntry[] entries, CacheEntry newEntry) {
    int initialBucketIndex = initialBucketIndex(entries, newEntry.valueHash());

    // initial scan to see if there's an empty slot or marker entry is already present
    double lowestHits = Double.MAX_VALUE;
    int lfuIndex = -1;
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null || entry.hits() == 0) {
        entries[index] = newEntry;
        return false;
      } else {
        double hits = entry.hits();
        if (hits < lowestHits) {
          lowestHits = hits;
          lfuIndex = index;
        }
      }
    }

    // If we get here, then we're evicting the LRU
    entries[lfuIndex] = newEntry;
    return true;
  }

  static final int initialBucketIndex(CacheEntry[] entries, int valueHash) {
    return valueHash & (entries.length - 1);
  }

  static final int initialBucketIndex(boolean[] marks, int valueHash) {
    return valueHash & (marks.length - 1);
  }

  static final boolean reverseMark(boolean[] marks, int newValueHash) {
    int index = initialBucketIndex(marks, newValueHash);
    boolean wasMarked = marks[index];
    marks[index] = !wasMarked;
    return wasMarked;
  }

  static final class CacheEntry {
    final int valueHash;
    final String value;
    final byte[] valueUtf8;

    boolean promoted = false;
    double hitCount = 0;

    public CacheEntry(int valueHash, String value) {
      this.valueHash = valueHash;
      this.value = value;
      this.valueUtf8 = utf8(value);
    }

    boolean matches(CacheEntry thatEntry) {
      return (this == thatEntry) || this.matches(thatEntry.valueHash, thatEntry.value);
    }

    boolean matches(int valueHash, String value) {
      return (this.valueHash == valueHash) && value.equals(this.value);
    }

    int valueHash() {
      return this.valueHash;
    }

    double hits() {
      return this.hitCount;
    }

    byte[] utf8() {
      return this.valueUtf8;
    }

    double hit() {
      this.hitCount += 1;

      return this.hitCount;
    }

    boolean decay() {
      this.hitCount *= HIT_DECAY;

      return (this.hitCount < PURGE_THRESHOLD);
    }

    static final byte[] utf8(String value) {
      return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
      if (this.value == null) {
        return "marker";
      } else {
        return this.value + " - hits: " + this.hitCount;
      }
    }
  }
}

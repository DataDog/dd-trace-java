package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.EncodingCache;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 2-level generational cache of UTF8 values - primarily intended to be used for tag values
 *
 * <p>Cache is designed to take advantage of low cardinality tags to avoid repeated UTF8 encodings
 * while also minimizing cache overhead and churn from high cardinality tags.
 *
 * <p>NOTE: The aim of this cache is to reduce allocation overhead -- not CPU overhead. Using the
 * cache has higher CPU overhead than simply calling {@link
 * String#getBytes(java.nio.charset.Charset)}.
 *
 * <p>The cache is thread safe.
 */
/*
 * Cache works by using a 2-level promotion based scheme.
 *
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
 * These first requests are tracked via edenMarkers which indicate if there was a
 * previously unsatisfied request to the same initial cache line.
 *
 * If there was a request, then CacheEntry is created and stored into edenEntries.
 * NOTE: The eden line marking process is imprecise and subject to request
 * ordering issues.  But given that low cardinality entries are more likely to repeat
 * next, imperically this scheme works well.
 *
 * If a collision occurs in the cache, linear probing is used to check other slots.
 * New cache entries fill any available slot within the probing window.
 *
 * If a subsequent request, finds a matching item in edenEntries.  The hit count
 * of the CacheEntry is bumped.  If the CacheEntry exceeds the current promotion
 * threshold, then the CacheEntry is inserted into promotionEntries -- freeing up
 * a slot in edenEntries.  If there isn't an available slot in promotionEntries,
 * the LRU: least recently used promotionEntry is evicted.
 *
 * If there are no available slots in edenEntries for a newly created CacheEntry...
 *
 * First, attempt to early promote the MFU: most frequently used CacheEntry from
 * edenEntries to promotedEntries (without eviction).
 *
 * If there's no space in promotedEntries to promote the MFU, then evict the
 * LFU: least frequently used entry from edenEntries instead.
 *
 *
 * LRU based eviction of the promotedEntries works on tagging with the last hit time.
 * The access time can be provided directly to GenerationalUtf8Cache#getUtf8 or can
 * be refreshed periodically by calling GenerationalUtf8Cache#updateAccessTime.
 *
 * If there's a natural transaction boundary around the UTF8 cache,
 * calling ValueUtf8Cache#reclibrate will adjust promotion thresholds to
 * provide better cache utilization.
 */
public final class GenerationalUtf8Cache implements EncodingCache {
  private static final int MAX_PROBES = 4;

  private static final int MIN_PROMOTION_TRESHOLD = 2;
  private static final int INITIAL_PROMOTION_THRESHOLD = 10;

  private static final double SCORE_DECAY = 0.8D;
  private static final double PURGE_THRESHOLD = 0.1D;
  private static final double PROMOTION_THRESHOLD_ADJ_FACTOR = 1.5;

  private final CacheEntry[] edenEntries;
  private final int[] edenMarkers;

  private final CacheEntry[] tenuredEntries;

  private long accessTimeMs;
  private double promotionThreshold = INITIAL_PROMOTION_THRESHOLD;

  int edenHits = 0;
  int promotedHits = 0;
  int earlyPromotions = 0;
  int promotions = 0;
  int edenEvictions = 0;
  int tenuredEvictions = 0;

  public GenerationalUtf8Cache() {
    this.accessTimeMs = System.currentTimeMillis();

    // These sizes must be powers of 2
    this.edenEntries = new CacheEntry[64];
    this.edenMarkers = new int[64];

    // The size must be a power of 2
    this.tenuredEntries = new CacheEntry[128];
  }

  /** Updates access time used @link {@link #getUtf8(String, String)} to the provided value */
  public void updateAccessTime(long accessTimeMs) {
    this.accessTimeMs = accessTimeMs;
  }

  /** Updates access time to the @link {@link System#currentTimeMillis()} */
  public void refreshAcessTime() {
    this.updateAccessTime(System.currentTimeMillis());
  }

  public synchronized void recalibrate() {
    this.recalibrate(System.currentTimeMillis());
  }

  /**
   * Recalibrates the cache
   * Applies a decay to existing entries - and purges entries below the PURGE_THRESHOLD
   * 
   * Adjusts the promotion threshold depending on ratio of promotions to 
   * evictions, since prior recalibration
   * 
   * While still racy this method is synchronized to avoid simultaneous recalibrations
   */
  public void recalibrate(long accessTimeMs) {
    this.accessTimeMs = accessTimeMs;

    CacheEntry[] thisEntries = this.edenEntries;
    for (int i = 0; i < thisEntries.length; ++i) {
      CacheEntry entry = thisEntries[i];
      if (entry == null) continue;

      boolean purge = entry.decay();
      if (purge) this.edenEntries[i] = null;
    }

    Arrays.fill(this.edenMarkers, 0);

    int totalPromotions = this.promotions + this.earlyPromotions;
    if (totalPromotions == 0 && this.promotionThreshold >= MIN_PROMOTION_TRESHOLD) {
      this.promotionThreshold /= PROMOTION_THRESHOLD_ADJ_FACTOR;
    } else if (totalPromotions > this.tenuredEvictions / 2) {
      this.promotionThreshold *= PROMOTION_THRESHOLD_ADJ_FACTOR;
    }

    this.edenHits = 0;
    this.promotedHits = 0;
    this.earlyPromotions = 0;
    this.promotions = 0;
    this.edenEvictions = 0;
    this.tenuredEvictions = 0;
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
    return this.getUtf8(value, this.accessTimeMs);
  }

  /**
   * Returns the UTF-8 encoding of value -- using a cache value if available If there is cache hit,
   * the specified accessTimeMs is used to update the cache entry
   */
  public final byte[] getUtf8(String value, long accessTimeMs) {
    int adjHash = CacheEntry.adjHash(value);

    CacheEntry[] localEntries = this.edenEntries;
    long lookupTimeMs = this.accessTimeMs;

    int matchingLocalIndex = lookupEntryIndex(localEntries, adjHash, value, lookupTimeMs);
    if (matchingLocalIndex != -1) {
      CacheEntry localEntry = localEntries[matchingLocalIndex];

      double hits = localEntry.hit(lookupTimeMs);
      if (hits > this.promotionThreshold) {
        // mark promoted first - to avoid racy insertions
        this.promotions += 1;

        boolean evicted = lruInsert(this.tenuredEntries, localEntry);
        if (evicted) this.tenuredEvictions += 1;

        localEntries[matchingLocalIndex] = null;
      }

      this.edenHits += 1;
      return localEntry.utf8();
    }

    CacheEntry[] promotedEntries = this.tenuredEntries;
    int matchingPromotedIndex = lookupEntryIndex(promotedEntries, adjHash, value, lookupTimeMs);
    if (matchingPromotedIndex != -1) {
      CacheEntry promotedEntry = promotedEntries[matchingPromotedIndex];

      promotedEntry.hit(lookupTimeMs);

      this.promotedHits += 1;
      return promotedEntry.utf8();
    }

    boolean wasMarked = mark(this.edenMarkers, adjHash);

    // If slot isn't marked, this is likely the first request
    // Don't create an entry yet
    if (!wasMarked) return CacheEntry.utf8(value);

    CacheEntry newEntry = new CacheEntry(adjHash, value);
    // First request was swallowed by marking, so double hit
    newEntry.hit(lookupTimeMs);
    newEntry.hit(lookupTimeMs);

    // search for empty slot or failing that the MFU entry
    int localMfuIndex = findFirstAvailableOrMfuIndex(localEntries, adjHash);
    CacheEntry localMfuEntry = localEntries[localMfuIndex];

    // Found an empty slot - fill it
    if (localMfuEntry == null) {
      localEntries[localMfuIndex] = newEntry;
      return newEntry.utf8();
    }

    // See if we can early promote the local MFU entry into the global cache
    // Early promotion doesn't evict from the global cache
    int globalAvailableIndex = findAvailableIndex(promotedEntries, localMfuEntry.adjHash());
    if (globalAvailableIndex != -1) {
      promotedEntries[globalAvailableIndex] = localMfuEntry;
      this.earlyPromotions += 1;

      localEntries[localMfuIndex] = newEntry;
      return CacheEntry.utf8(value);
    }

    // No empty slot - or space to promote into the global cache
    // Insert into local cache while evicting the LFU
    boolean evicted = lfuInsert(localEntries, newEntry);
    if (evicted) this.tenuredEvictions += 1;

    return newEntry.utf8();
  }

  static final int findAvailableIndex(CacheEntry[] entries, int newAdjHash) {
    int initialBucketIndex = initialBucketIndex(entries, newAdjHash);
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null || entry.isPurgeable()) return index;
    }
    return -1;
  }

  static final int findFirstAvailableOrMfuIndex(CacheEntry[] entries, int newAdjHash) {
    double mfuScore = Double.MIN_VALUE;
    int mfuIndex = -1;

    int initialBucketIndex = initialBucketIndex(entries, newAdjHash);
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null) return index;

      double score = entry.score();
      if (score > mfuScore) {
        mfuScore = score;
        mfuIndex = index;
      }
    }
    return mfuIndex;
  }

  static final boolean mark(int[] marks, int newAdjHash) {
    int index = initialBucketIndex(marks, newAdjHash);

    int priorMarkHash = marks[index];
    marks[index] = newAdjHash;

    return (priorMarkHash == newAdjHash);
  }

  static final boolean lfuInsert(CacheEntry[] entries, CacheEntry newEntry) {
    int initialBucketIndex = initialBucketIndex(entries, newEntry.adjHash());

    // initial scan to see if there's an empty slot or marker entry is already present
    double lowestScore = Double.MAX_VALUE;
    int lfuIndex = -1;
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null || entry.isPurgeable()) {
        entries[index] = newEntry;
        return false;
      } else {
        double score = entry.score();
        if (score < lowestScore) {
          lowestScore = score;
          lfuIndex = index;
        }
      }
    }

    // If we get here, then we're evicted the LRU
    entries[lfuIndex] = newEntry;
    return true;
  }

  static final boolean lruInsert(CacheEntry[] entries, CacheEntry newEntry) {
    int initialBucketIndex = initialBucketIndex(entries, newEntry.adjHash());

    // initial scan to see if there's an empty slot or entry is already present
    long lowestUsedMs = Long.MAX_VALUE;
    int lruIndex = -1;
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null) {
        entries[index] = newEntry;
        return false;
      } else if (entry.matches(newEntry)) {
        entries[index] = newEntry;
        return false;
      } else {
        long lastUsedMs = entry.lastUsedMs();
        if (lastUsedMs < lowestUsedMs) {
          lowestUsedMs = lastUsedMs;
          lruIndex = index;
        }
      }
    }

    entries[lruIndex] = newEntry;
    return true;
  }

  static final int initialBucketIndex(CacheEntry[] entries, int adjHash) {
    return adjHash & (entries.length - 1);
  }

  static final int initialBucketIndex(int[] marks, int adjHash) {
    return adjHash & (marks.length - 1);
  }

  static final int lookupEntryIndex(
      CacheEntry[] entries, int adjHash, String value, long lookupTimeMs) {
    int initialBucketIndex = initialBucketIndex(entries, adjHash);
    for (int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry != null && entry.matches(adjHash, value)) {
        return index;
      }
    }
    return -1;
  }

  static final class CacheEntry {
    final int adjHash;
    final String value;
    final byte[] valueUtf8;

    boolean promoted = false;
    long lastUsedMs = 0;
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

    long lastUsedMs() {
      return this.lastUsedMs;
    }

    byte[] utf8() {
      return this.valueUtf8;
    }

    double hit(long lastUsedMs) {
      this.lastUsedMs = lastUsedMs;
      this.score += 1;

      return this.score;
    }

    boolean decay() {
      this.score *= SCORE_DECAY;

      return this.isPurgeable();
    }

    boolean isPurgeable() {
      return (this.score < PURGE_THRESHOLD);
    }

    static final int adjHash(String value) {
      int hash = value.hashCode();
      return (hash == 0) ? 0xDA7AD06 : hash;
    }

    static final byte[] utf8(String value) {
      return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
      return this.value + " - score: " + this.score + " used (ms): " + this.lastUsedMs;
    }
  }
}

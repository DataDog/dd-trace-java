package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.EncodingCache;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;

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
@SuppressFBWarnings(
    value = "IS2_INCONSISTENT_SYNC",
    justification =
        "stat updates are deliberately racy - sync is only used to prevent simultaneous bulk updates")
public final class GenerationalUtf8Cache implements EncodingCache {
  static final int MAX_EDEN_CAPACITY = 512;
  static final int MAX_TENURED_CAPACITY = 1024;

  private static final int MAX_EDEN_PROBES = 4;
  private static final int MAX_TENURED_PROBES = 8;

  private static final int MIN_PROMOTION_TRESHOLD = 2;
  private static final int INITIAL_PROMOTION_THRESHOLD = 16;

  private static final double SCORE_DECAY = 0.5D;
  private static final double PURGE_THRESHOLD = 0.25D;
  private static final double PROMOTION_THRESHOLD_ADJ_FACTOR = 1.5;

  private static final double EDEN_PROPORTION = 1D / 3D;
  private static final double TENURED_PROPORTION = 1 - EDEN_PROPORTION;

  static final int MAX_ENTRY_LEN = 256;

  private final CacheEntry[] edenEntries;
  private final int[] edenMarkers;

  private final CacheEntry[] tenuredEntries;

  private long accessTimeMs;
  private double promotionThreshold = INITIAL_PROMOTION_THRESHOLD;

  int edenHits = 0;
  int tenuredHits = 0;
  int earlyPromotions = 0;
  int promotions = 0;
  int edenEvictions = 0;
  int tenuredEvictions = 0;

  public GenerationalUtf8Cache(int capacity) {
    this.accessTimeMs = System.currentTimeMillis();

    int edenCapacity = (int) (capacity * EDEN_PROPORTION);
    int edenSize = Caching.cacheSizeFor(Math.min(edenCapacity, MAX_EDEN_CAPACITY));

    // These sizes must be powers of 2
    this.edenEntries = new CacheEntry[edenSize];
    this.edenMarkers = new int[edenSize];

    int tenuredCapacity = (int) (capacity * TENURED_PROPORTION);
    int tenuredSize = Caching.cacheSizeFor(Math.min(tenuredCapacity, MAX_TENURED_CAPACITY));

    // The size must be a power of 2
    this.tenuredEntries = new CacheEntry[tenuredSize];
  }

  public GenerationalUtf8Cache(int edenCapacity, int tenuredCapacity) {
    this.accessTimeMs = System.currentTimeMillis();

    int edenSize = Caching.cacheSizeFor(Math.min(tenuredCapacity, MAX_EDEN_CAPACITY));
    this.edenEntries = new CacheEntry[edenSize];
    this.edenMarkers = new int[edenSize];

    int tenuredSize = Caching.cacheSizeFor(Math.min(tenuredCapacity, MAX_TENURED_CAPACITY));
    this.tenuredEntries = new CacheEntry[tenuredSize];
  }

  public int edenCapacity() {
    return this.edenEntries.length;
  }

  public int tenuredCapacity() {
    return this.tenuredEntries.length;
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
   * Recalibrates the cache Applies a decay to existing entries - and purges entries below the
   * PURGE_THRESHOLD
   *
   * <p>Adjusts the promotion threshold depending on ratio of promotions to evictions, since prior
   * recalibration
   *
   * <p>While still racy this method is synchronized to avoid simultaneous recalibrations
   */
  public synchronized void recalibrate(long accessTimeMs) {
    this.accessTimeMs = accessTimeMs;

    recalibrate(this.edenEntries);
    Caching.reset(this.edenMarkers);
    recalibrate(this.tenuredEntries);

    int totalPromotions = this.promotions + this.earlyPromotions;
    if (totalPromotions == 0 && this.promotionThreshold >= MIN_PROMOTION_TRESHOLD) {
      this.promotionThreshold /= PROMOTION_THRESHOLD_ADJ_FACTOR;
    } else if (totalPromotions > this.tenuredEvictions / 2) {
      this.promotionThreshold *= PROMOTION_THRESHOLD_ADJ_FACTOR;
    }

    this.edenHits = 0;
    this.tenuredHits = 0;
    this.earlyPromotions = 0;
    this.promotions = 0;
    this.edenEvictions = 0;
    this.tenuredEvictions = 0;
  }

  static final void recalibrate(CacheEntry[] entries) {
    for (int i = 0; i < entries.length; ++i) {
      CacheEntry entry = entries[i];
      if (entry == null) continue;

      boolean purge = entry.decay();
      if (purge) entries[i] = null;
    }
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
    if (value.length() > MAX_ENTRY_LEN) return CacheEntry.utf8(value);

    int adjHash = Caching.adjHash(value);
    long lookupTimeMs = this.accessTimeMs;

    CacheEntry[] tenuredEntries = this.tenuredEntries;
    int matchingTenuredIndex = lookupEntryIndex(tenuredEntries, MAX_TENURED_PROBES, adjHash, value);
    if (matchingTenuredIndex != -1) {
      CacheEntry tenuredEntry = tenuredEntries[matchingTenuredIndex];

      tenuredEntry.hit(lookupTimeMs);

      this.tenuredHits += 1;
      return tenuredEntry.utf8();
    }

    CacheEntry[] edenEntries = this.edenEntries;
    int matchingEdenIndex = lookupEntryIndex(edenEntries, MAX_EDEN_PROBES, adjHash, value);
    if (matchingEdenIndex != -1) {
      CacheEntry edenEntry = edenEntries[matchingEdenIndex];

      double hits = edenEntry.hit(lookupTimeMs);
      if (hits > this.promotionThreshold) {
        // mark promoted first - to avoid racy insertions
        this.promotions += 1;

        boolean evicted = lruInsert(this.tenuredEntries, MAX_TENURED_PROBES, edenEntry);
        if (evicted) this.tenuredEvictions += 1;

        edenEntries[matchingEdenIndex] = null;
      }

      this.edenHits += 1;
      return edenEntry.utf8();
    }

    boolean wasMarked = Caching.mark(this.edenMarkers, adjHash);

    // If slot isn't marked, this is likely the first request
    // Don't create an entry yet
    if (!wasMarked) return CacheEntry.utf8(value);

    CacheEntry newEntry = new CacheEntry(adjHash, value);
    // First request was swallowed by marking, so double hit
    newEntry.hit(lookupTimeMs);
    newEntry.hit(lookupTimeMs);

    // search for empty slot or failing that the MFU entry
    int edenMfuIndex = findFirstAvailableOrMfuIndex(edenEntries, MAX_EDEN_PROBES, adjHash);
    CacheEntry edenMfuEntry = edenEntries[edenMfuIndex];

    // Found an empty slot - fill it
    if (edenMfuEntry == null) {
      edenEntries[edenMfuIndex] = newEntry;
      return newEntry.utf8();
    }

    // See if we can early promote the local MFU entry into the global cache
    // Early promotion doesn't evict from the global cache

    // NOTE: Need to make sure to use hash of the entry being promoted,
    // since it may differ from the requested hash
    int tenuredAvailableIndex =
        findAvailableIndex(tenuredEntries, MAX_TENURED_PROBES, edenMfuEntry.adjHash());
    if (tenuredAvailableIndex != -1) {
      tenuredEntries[tenuredAvailableIndex] = edenMfuEntry;
      this.earlyPromotions += 1;

      edenEntries[edenMfuIndex] = newEntry;
      return newEntry.utf8();
    }

    // No empty slot - or space to promote into the global cache
    // Insert into local cache while evicting the LFU
    boolean evicted = lfuInsert(edenEntries, MAX_EDEN_PROBES, newEntry);
    if (evicted) this.edenEvictions += 1;

    return newEntry.utf8();
  }

  static final int findAvailableIndex(CacheEntry[] entries, int numProbes, int newAdjHash) {
    int initialBucketIndex = Caching.bucketIndex(entries, newAdjHash);
    for (int probe = 0, index = initialBucketIndex; probe < numProbes; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null || entry.isPurgeable()) return index;
    }
    return -1;
  }

  static final int findFirstAvailableOrMfuIndex(
      CacheEntry[] entries, int numProbes, int newAdjHash) {
    double mfuScore = Double.MIN_VALUE;
    int mfuIndex = -1;

    int initialBucketIndex = Caching.bucketIndex(entries, newAdjHash);
    for (int probe = 0, index = initialBucketIndex; probe < numProbes; ++probe, ++index) {
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

  static final boolean lfuInsert(CacheEntry[] entries, int numProbes, CacheEntry newEntry) {
    int initialBucketIndex = Caching.bucketIndex(entries, newEntry.adjHash());

    // initial scan to see if there's an empty slot or marker entry is already present
    double lowestScore = Double.MAX_VALUE;
    int lfuIndex = -1;
    for (int probe = 0, index = initialBucketIndex; probe < numProbes; ++probe, ++index) {
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

  static final boolean lruInsert(CacheEntry[] entries, int numProbes, CacheEntry newEntry) {
    int initialBucketIndex = Caching.bucketIndex(entries, newEntry.adjHash());

    // initial scan to see if there's an empty slot or entry is already present
    long lowestUsedMs = Long.MAX_VALUE;
    int lruIndex = -1;
    for (int probe = 0, index = initialBucketIndex; probe < numProbes; ++probe, ++index) {
      if (index >= entries.length) index = 0;

      CacheEntry entry = entries[index];
      if (entry == null || entry.matches(newEntry)) {
        entries[index] = newEntry;
        return false;
      }

      long lastUsedMs = entry.lastUsedMs();
      if (lastUsedMs < lowestUsedMs) {
        lowestUsedMs = lastUsedMs;
        lruIndex = index;
      }
    }

    entries[lruIndex] = newEntry;
    return true;
  }

  static final int lookupEntryIndex(
      CacheEntry[] entries, int numProbes, int adjHash, String value) {
    int initialBucketIndex = Caching.bucketIndex(entries, adjHash);
    for (int probe = 0, index = initialBucketIndex; probe < numProbes; ++probe, ++index) {
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

    static final byte[] utf8(String value) {
      return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
      return this.value + " - score: " + this.score + " used (ms): " + this.lastUsedMs;
    }
  }
}

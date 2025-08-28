package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.EncodingCache;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 
 * 2-level generational cache of UTF8 values - primarily intended to be used for tag values
 * 
 * Cache is designed to take advantage of low cardinality tags to avoid 
 * repeated UTF8 encodings while also minimizing cache overhead and 
 * churn from high cardinality tags.
 * 
 * NOTE: The aim of this cache is to reduce allocation overhead -- not CPU overhead.
 * Using the cache has higher CPU overhead than simply calling {@link String#getBytes(java.nio.charset.Charset)}.
 *  
 * The cache is thread safe.
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
 * These first requests are tracked via edenMarkers which indicate if there was 
 * previously an unsatisfied request to the same initial cache line.
 * 
 * If there was a request, then CacheEntry is created and stored into edenEntries.
 * NOTE: The eden line marking process is imprecise and subject to request 
 * ordering issues, but given that low cardinality entries are more likely to repeat
 * next.
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
 * Attempt to early promote the MFU: most frequently used CacheEntry from 
 * edenEntries to promotedEntries (without eviction).
 * 
 * If there's no space in promotedEntries to promote the MFU, then evict the 
 * LFU: least frequently used entry from edenEntries instead.
 * 
 * 
 * LRU based eviction of the promotedEntries works on tagging with the last hit time.
 * The access time can be provided directly to ValueUtf8Cache#getUtf8 or can 
 * be refreshed periodically by calling ValueUtf8Cache#updateAccessTime.
 * 
 * If there's a natural transaction boundary around the UTF8 cache, 
 * calling ValueUtf8Cache#recalibrateThresholds will adjust promotion 
 * thresholds to provide better cache utilization.
 */
public final class GenerationalUtf8Cache implements EncodingCache {
  private static final int MAX_PROBES = 8;
  
  private static final int MIN_PROMOTION_TRESHOLD = 2;
  private static final int INITIAL_PROMOTION_THRESHOLD = 10;
  
  private static final double HIT_DECAY = 0.8D;
  private static final double PURGE_THRESHOLD = 0.25D;
  
  private final CacheEntry[] edenEntries;
  private final boolean[] edenMarkers;
  
  private final CacheEntry[] promotedEntries;
  
  private long accessTimeMs;
  private double promotionThreshold = INITIAL_PROMOTION_THRESHOLD;
  
  int edenHits = 0;
  int promotedHits = 0;
  int earlyPromotions = 0;
  int promotions = 0;
  int edenEvictions = 0;
  int promotedEvictions = 0;
  
  public GenerationalUtf8Cache() {
	this.accessTimeMs = System.currentTimeMillis();
	
	// These sizes must be powers of 2
	this.edenEntries = new CacheEntry[256];
	this.edenMarkers = new boolean[256];
	
	// The size must be a power of 2
	this.promotedEntries = new CacheEntry[512];
  }
  
  /**
   * Updates access time used @link {@link #getUtf8(String, String)} to the provided value
   */
  public void updateAccessTime(long accessTimeMs) {
	this.accessTimeMs = accessTimeMs;
  }
  
  /**
   * Updates access time to the @link {@link System#currentTimeMillis()}
   */
  public void refreshAcessTime() {
	this.updateAccessTime(System.currentTimeMillis());
  }
  
  public void recalibrate() {
	this.recalibrate(System.currentTimeMillis());
  }
  
  /**
   * Recalibrates promotion threshold based on promotion & eviction statistics, 
   * since last calibration - resets statistics
   * @param accessTimeMs
   */
  public void recalibrate(long accessTimeMs) {
	this.accessTimeMs = accessTimeMs;
	
	CacheEntry[] thisEntries = this.edenEntries;
	for ( int i = 0; i < thisEntries.length; ++i ) {
	  CacheEntry entry = thisEntries[i];	  
	  if ( entry == null ) continue;
	  
	  boolean purge = entry.decay();
	  if ( purge ) this.edenEntries[i] = null;
	}
	
	Arrays.fill(this.edenMarkers, false);
	
	int totalPromotions = this.promotions + this.earlyPromotions;
	if ( totalPromotions == 0 && this.promotionThreshold >= MIN_PROMOTION_TRESHOLD ) {
	  this.promotionThreshold /= 1.5;
	} else if ( totalPromotions > this.promotedEvictions / 2 ) {
	  this.promotionThreshold *= 1.5;
	}
	
	this.edenHits = 0;
	this.promotedHits = 0;
	this.earlyPromotions = 0;
	this.promotions = 0;
	this.edenEvictions = 0;
	this.promotedEvictions = 0;
  }
  
  @Override
  public byte[] encode(CharSequence charSeq) {
	if ( charSeq instanceof String ) {
	  String str = (String)charSeq;
	  return this.getUtf8(str);
	} else {
	  return null;
	}
  }
  
  /**
   * Returns the UTF-8 encoding of value -- using a cache value if available
   */
  public final byte[] getUtf8(String value) {
    return this.getUtf8(value, this.accessTimeMs);
  }
  
  /**
   * Returns the UTF-8 encoding of value -- using a cache value if available
   * If there is cache hit, the specified accessTimeMs is used to update the cache entry
   */
  public final byte[] getUtf8(String value, long accessTimeMs) {
	int valueHash = value.hashCode();
	
	CacheEntry[] localEntries = this.edenEntries;
	long lookupTimeMs = this.accessTimeMs;
	
	int matchingLocalIndex = lookupEntry(localEntries, valueHash, value, lookupTimeMs);
	if ( matchingLocalIndex != -1 ) {
	  CacheEntry localEntry = localEntries[matchingLocalIndex];
	  
	  double hits = localEntry.hit(lookupTimeMs);
	  if ( hits > this.promotionThreshold ) {
		// mark promoted first - to avoid racy insertions
		this.promotions += 1;
		
		boolean evicted = lruInsert(this.promotedEntries, localEntry);
		if ( evicted ) this.promotedEvictions += 1;
		
		localEntries[matchingLocalIndex] = null;
	  }
	  
	  this.edenHits += 1;
	  return localEntry.utf8();
	}
	
	CacheEntry[] promotedEntries = this.promotedEntries;
	int matchingPromotedIndex = lookupEntry(promotedEntries, valueHash, value, lookupTimeMs);
	if ( matchingPromotedIndex != -1 ) {
   	  CacheEntry promotedEntry = promotedEntries[matchingPromotedIndex];
		  
	  promotedEntry.hit(lookupTimeMs);
	  
	  this.promotedHits += 1;
	  return promotedEntry.utf8();
	}
	
	boolean wasMarked = reverseMark(this.edenMarkers, valueHash);
	
	// If slot isn't marked, this is likely the first request
	// Don't create an entry yet
	if ( !wasMarked ) return CacheEntry.utf8(value);
	
	CacheEntry newEntry = new CacheEntry(valueHash, value);
	// First request was swallowed by marking, so double hit
	newEntry.hit(lookupTimeMs);
	newEntry.hit(lookupTimeMs);
	
	// search for empty slot or failing that the MFU entry
	int localMfuIndex = findFirstAvailableOrMfuIndex(localEntries, valueHash);
	CacheEntry localMfuEntry = localEntries[localMfuIndex];
	
	// Found an empty slot - fill it
	if ( localMfuEntry == null ) {
	  localEntries[localMfuIndex] = newEntry;
	  return newEntry.utf8();
	}
	
	// See if we can early promote the local MFU entry into the global cache
	// Early promotion doesn't evict from the global cache
	int globalAvailableIndex = findAvailable(promotedEntries, localMfuEntry.valueHash());
	if ( globalAvailableIndex != -1 ) {
	  promotedEntries[globalAvailableIndex] = localMfuEntry;
	  this.earlyPromotions += 1;
	  
	  localEntries[localMfuIndex] = newEntry;
	  return CacheEntry.utf8(value);
	}
	
	// No empty slot - or space to promote into the global cache
	// Insert into local cache while evicting the LFU	
	boolean evicted = lfuInsert(localEntries, newEntry);
	if ( evicted ) this.promotedEvictions += 1;
	
	return newEntry.utf8();
  }
  
  static final int findAvailable(CacheEntry[] entries, int newValueHash) {
	int initialBucketIndex = initialBucketIndex(entries, newValueHash);
	for ( int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index ) {
	  if ( index >= entries.length ) index = 0;
	  
	  CacheEntry entry = entries[index];
	  if ( entry == null || entry.hits() == 0 ) return index;
	}
	return -1;
  }
  
  static final int findFirstAvailableOrMfuIndex(CacheEntry[] entries, int newValueHash) {
	double mfuHits = Double.MIN_VALUE;
	int mfuIndex = -1;
	
	int initialBucketIndex = initialBucketIndex(entries, newValueHash);
	for ( int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index ) {
	  if ( index >= entries.length ) index = 0;
	  
	  CacheEntry entry = entries[index];
	  if ( entry == null ) return index;
	  
	  double hits = entry.hits();
	  if ( hits > mfuHits ) {
		mfuHits = hits;
		mfuIndex = index;
	  }
	}
	return mfuIndex;
  }
  
  static final boolean reverseMark(boolean[] marks, int newValueHash) {
	int index = initialBucketIndex(marks, newValueHash);
	boolean wasMarked = marks[index];
	marks[index] = !wasMarked;
	return wasMarked;
  }
  
  static final boolean lfuInsert(CacheEntry[] entries, CacheEntry newEntry) {
    int initialBucketIndex = initialBucketIndex(entries, newEntry.valueHash());
    
    // initial scan to see if there's an empty slot or marker entry is already present
    double lowestHits = Double.MAX_VALUE;
    int lfuIndex = -1;
	for ( int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index ) {
	  if ( index >= entries.length ) index = 0;
	  
	  CacheEntry entry = entries[index];
	  if ( entry == null || entry.hits() == 0 ) {
		entries[index] = newEntry;
		return false;
	  } else {
		double hits = entry.hits();
		if ( hits < lowestHits ) {
		  lowestHits = hits;
		  lfuIndex = index;
		}
	  }
	}
	
	// If we get here, then we're evicted the LRU
	entries[lfuIndex] = newEntry;
	return true;
  }
  
  static final boolean lruInsert(CacheEntry[] entries, CacheEntry newEntry) {
    int initialBucketIndex = initialBucketIndex(entries, newEntry.valueHash());
    
    // initial scan to see if there's an empty slot or entry is already present
    long lowestUsedMs = Long.MAX_VALUE;
    int lruIndex = -1;
	for ( int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index ) {
	  if ( index >= entries.length ) index = 0;
	  
	  CacheEntry entry = entries[index];
	  if ( entry == null ) {
		entries[index] = newEntry;
		return false;
	  } else if ( entry.matches(newEntry) ) {
		entries[index] = newEntry;
		return false;
	  } else {
		long lastUsedMs = entry.lastUsedMs();
		if ( lastUsedMs < lowestUsedMs ) {
		  lowestUsedMs = lastUsedMs;
		  lruIndex = index;
		}
	  }
	}
	
	entries[lruIndex] = newEntry;
	return true;
  }
  
  static final int initialBucketIndex(CacheEntry[] entries, int valueHash) {
    return valueHash & (entries.length - 1);
  }

  static final int initialBucketIndex(boolean[] marks, int valueHash) {
    return valueHash & (marks.length - 1);
  }
  
  static final int lookupEntry(
    CacheEntry[] entries,
    int valueHash, String value,
    long lookupTimeMs)
  {
	int initialBucketIndex = initialBucketIndex(entries, valueHash);
	for ( int probe = 0, index = initialBucketIndex; probe < MAX_PROBES; ++probe, ++index ) {
	  if ( index >= entries.length ) index = 0;
	  
	  CacheEntry entry = entries[index];
	  if ( entry != null && entry.matches(valueHash, value) ) {
		return index;
	  }
	}
	return -1;
  }
  
  static final int bucketHash(int tagHash, int valueHash) {
	return tagHash + 31 * valueHash;
  }
  
  static final class CacheEntry {
	final int valueHash;
    final String value;
    final byte[] valueUtf8;
    
    boolean promoted = false;
    long lastUsedMs = 0;
    double hitCount = 0;
    
    public CacheEntry(int valueHash, String value) {
      this.valueHash = valueHash;
      this.value = value;
      this.valueUtf8 = utf8(value);
    }
    
    boolean matches(CacheEntry thatEntry ) {
      return ( this == thatEntry ) || this.matches(thatEntry.valueHash, thatEntry.value);
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
    
    long lastUsedMs() {
      return this.lastUsedMs;
    }
    
    byte[] utf8() {
      return this.valueUtf8;
    }
    
    double hit(long lastUsedMs) {
      this.lastUsedMs = lastUsedMs;
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
      if ( this.value == null ) {
    	return "marker";
      } else {
    	return this.value + " - hits: " + this.hitCount + " used (ms): " + this.lastUsedMs;
      }
    }
  }
}

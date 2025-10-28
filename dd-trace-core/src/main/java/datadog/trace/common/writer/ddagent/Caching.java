package datadog.trace.common.writer.ddagent;

import java.util.Arrays;

/** Some common static functions used by simple & generational caches */
final class Caching {
  private Caching() {}

  /**
   * Provides the cache size that holds the requestedCapacity
   *
   * @param requestedCapacity > 0
   * @return size >= requestedCapacity
   */
  static final int cacheSizeFor(int requestedCapacity) {
    int pow;
    for (pow = 1; pow < requestedCapacity; pow *= 2) ;
    return pow;
  }

  /** Provides an "adjusted" (e.g. non-zero) hash for the given String */
  static final int adjHash(String value) {
    int hash = value.hashCode();
    return (hash == 0) ? 0xDA7AD06 : hash;
  }

  /** Resets markers to zero */
  static final void reset(int[] marks) {
    Arrays.fill(marks, 0);
  }

  /**
   * Changes the mark status of the corresponding slot in the marking array. If there was previously
   * a matching mark, resets the slot to zero and returns true If there was previously a mismatching
   * mark, updates the slot and returns false
   *
   * <p>A return value of true indicates that the requested value has likely been seen previously
   * and cache entry should be created.
   */
  static final boolean mark(int[] marks, int newAdjHash) {
    int index = bucketIndex(marks, newAdjHash);

    // This is the 4th iteration of the marking strategy
    // First version - used a mark entry, but that would prematurely
    // burn a slot in the cache
    // Second version - used a mark boolean, that worked well, but
    // was a overly permissive in allowing the next request to the same slot
    // to immediately create a CacheEntry
    // Third version - used a mark hash that to match exactly,
    // that could lead to access order fights over the cache slot
    // So this version is a hybrid of 2nd & 3rd, using a bloom filter
    // that effectively degenerates to a boolean

    // This approach provides a nice balance when there's an A-B-A access pattern
    // The first A will mark the slot
    // Then B will mark the slot with A | B
    // Then either A or B can claim and reset the slot

    int priorMarkHash = marks[index];
    boolean match = ((priorMarkHash & newAdjHash) == newAdjHash);
    if (match) {
      marks[index] = 0;
    } else {
      marks[index] = priorMarkHash | newAdjHash;
    }
    return match;
  }

  /** Provides the corresponding index into the marking array */
  static final int bucketIndex(int[] marks, int adjHash) {
    return adjHash & (marks.length - 1);
  }

  /**
   * Provides the corresponding index into an entry array Assumes that array size was determined by
   * using {@Caching#cacheSizeFor}
   */
  static final <E> int bucketIndex(E[] entries, int adjHash) {
    return adjHash & (entries.length - 1);
  }
}

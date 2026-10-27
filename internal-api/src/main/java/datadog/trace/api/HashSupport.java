package datadog.trace.api;

import datadog.trace.api.TagMap.BucketGroup;
import datadog.trace.api.TagMap.Entry;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The raw bucket mechanics both tag stores share, over one fixed-size {@code Object[]} of buckets
 * of shareable, immutable {@link Entry} objects. A bucket holds a lone Entry directly; because
 * Entries are shared between maps, they cannot form a linked list, so colliding entries share a
 * fixed-size {@link BucketGroup}, chained when one fills. Removals are assumed rare, so groups are
 * never consolidated, but an emptied group is unlinked from its chain.
 *
 * <p>Stateless: each operation reports what it did through its return value -- put returns the
 * entry it replaced, remove the entry it removed -- and the calling store keeps its own count.
 * Copies share Entries and clone only BucketGroup chains. Iteration skips tombstones ({@link
 * Entry#tombstone}), which only an id store holds.
 */
final class HashSupport {
  private HashSupport() {}

  /** A fresh bucket array; its length must be a power of 2 for bucket masking. */
  static Object[] newBuckets() {
    return new Object[1 << 4];
  }

  /** The entry for {@code (hash, tag)}, tombstone or not, or null. */
  static Entry find(Object[] buckets, int hash, String tag) {
    return findInBucket(buckets[hash & (buckets.length - 1)], hash, tag);
  }

  /**
   * {@link #find} for a known tag, matching on its id rather than its name, so an id store never
   * needs a name. {@code hash} is {@code Entry.bucketHash(tagId)}.
   */
  static Entry findById(Object[] buckets, int hash, long tagId) {
    Object bucket = buckets[hash & (buckets.length - 1)];
    if (bucket instanceof Entry) {
      Entry tagEntry = (Entry) bucket;
      return tagEntry.tagHash == tagId ? tagEntry : null;
    } else if (bucket instanceof BucketGroup) {
      return ((BucketGroup) bucket).findInChainById(hash, tagId);
    }
    return null;
  }

  /** Finds an entry by hash and tag in one bucket: an Entry, a BucketGroup chain, or null. */
  private static Entry findInBucket(Object bucket, int hash, String tag) {
    if (bucket instanceof Entry) {
      Entry tagEntry = (Entry) bucket;
      return tagEntry.hash() == hash && tagEntry.matches(tag) ? tagEntry : null;
    } else if (bucket instanceof BucketGroup) {
      return ((BucketGroup) bucket).findInChain(hash, tag);
    }
    return null;
  }

  /** Inserts or replaces {@code newEntry}, returning the entry it replaced, or null if it added. */
  static Entry put(Object[] buckets, Entry newEntry) {
    Object[] thisBuckets = buckets;

    int newHash = newEntry.hash();
    int bucketIndex = newHash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    if (bucket == null) {
      thisBuckets[bucketIndex] = newEntry;

      return null;
    } else if (bucket instanceof Entry) {
      Entry existingEntry = (Entry) bucket;
      if (existingEntry.hash() == newHash && existingEntry.matches(newEntry.tag)) {
        thisBuckets[bucketIndex] = newEntry;

        // replaced existing entry - no size change
        return existingEntry;
      } else {
        thisBuckets[bucketIndex] =
            new BucketGroup(existingEntry.hash(), existingEntry, newHash, newEntry);

        return null;
      }
    } else if (bucket instanceof BucketGroup) {
      BucketGroup lastGroup = (BucketGroup) bucket;

      BucketGroup containingGroup = lastGroup.findContainingGroupInChain(newHash, newEntry.tag);
      if (containingGroup != null) {
        // replaced existing entry - no size change
        return containingGroup._replace(newHash, newEntry);
      }

      if (!lastGroup.insertInChain(newHash, newEntry)) {
        thisBuckets[bucketIndex] = new BucketGroup(newHash, newEntry, lastGroup);
      }
      return null;
    }

    // unreachable
    return null;
  }

  /** Removes the entry for {@code (hash, tag)}, returning it, or null if there was none. */
  static Entry remove(Object[] buckets, int hash, String tag) {
    Object[] thisBuckets = buckets;
    int bucketIndex = hash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    // null bucket case - do nothing
    if (bucket instanceof Entry) {
      Entry existingEntry = (Entry) bucket;
      if (existingEntry.hash() == hash && existingEntry.matches(tag)) {
        thisBuckets[bucketIndex] = null;

        return existingEntry;
      } else {
        return null;
      }
    } else if (bucket instanceof BucketGroup) {
      BucketGroup lastGroup = (BucketGroup) bucket;

      BucketGroup containingGroup = lastGroup.findContainingGroupInChain(hash, tag);
      if (containingGroup == null) {
        return null;
      }

      Entry existingEntry = containingGroup._remove(hash, tag);
      if (containingGroup._isEmpty()) {
        thisBuckets[bucketIndex] = lastGroup.removeGroupInChain(containingGroup);
      }

      return existingEntry;
    }
    return null;
  }

  /**
   * Copies {@code thatBuckets} into the empty {@code thisBuckets}, sharing Entry objects and
   * cloning only BucketGroup chains.
   */
  static void copyInto(Object[] thisBuckets, Object[] thatBuckets) {

    // Check against both thisBuckets.length && thatBuckets.length is to help the JIT do bound check
    // elimination
    for (int i = 0; i < thisBuckets.length && i < thatBuckets.length; ++i) {
      Object thatBucket = thatBuckets[i];

      // faster to explicitly null check first, then do instanceof
      if (thatBucket == null) {
        // do nothing
      } else if (thatBucket instanceof BucketGroup) {
        // if it is a BucketGroup, then need to clone
        BucketGroup thatGroup = (BucketGroup) thatBucket;

        thisBuckets[i] = thatGroup.cloneChain();
      } else { // if ( thatBucket instanceof Entry )
        thisBuckets[i] = thatBucket;
      }
    }
  }

  /**
   * Merges {@code thatBuckets} into {@code thisBuckets}, sharing Entry objects and cloning only
   * BucketGroup chains; incoming entries replace existing ones. Returns how many it added. Both
   * arrays must be the same length (stores don't expand, so buckets are perfectly aligned).
   */
  static int merge(Object[] thisBuckets, Object[] thatBuckets) {
    int added = 0;

    // Stores don't expand, so buckets are perfectly aligned
    // Check against both thisBuckets.length && thatBuckets.length is to help the JIT do bound check
    // elimination
    for (int i = 0; i < thisBuckets.length && i < thatBuckets.length; ++i) {
      Object thatBucket = thatBuckets[i];

      // if nothing incoming, nothing to do
      if (thatBucket == null) continue;

      Object thisBucket = thisBuckets[i];
      if (thisBucket == null) {
        // This bucket is null, easy case
        // Either copy over the sole entry or clone the BucketGroup chain

        if (thatBucket instanceof Entry) {
          thisBuckets[i] = thatBucket;
          added += 1;
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          BucketGroup thisNewGroup = thatGroup.cloneChain();
          thisBuckets[i] = thisNewGroup;
          added += thisNewGroup.sizeInChain();
        }
      } else if (thisBucket instanceof Entry) {
        // This bucket is a single entry, medium complexity case
        // If other side is an Entry - just merge the entries into a bucket
        // If other side is a BucketGroup - then clone the group and insert the entry normally into
        // the cloned group

        Entry thisEntry = (Entry) thisBucket;
        int thisHash = thisEntry.hash();

        if (thatBucket instanceof Entry) {
          Entry thatEntry = (Entry) thatBucket;
          int thatHash = thatEntry.hash();

          if (thisHash == thatHash && thisEntry.matches(thatEntry.tag())) {
            thisBuckets[i] = thatEntry;
            // replacing entry, no size change
          } else {
            thisBuckets[i] =
                new BucketGroup(
                    thisHash, thisEntry,
                    thatHash, thatEntry);
            added += 1;
          }
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          // Clone the other group, then place this entry into that group
          BucketGroup thisNewGroup = thatGroup.cloneChain();
          int thisNewGroupSize = thisNewGroup.sizeInChain();

          Entry incomingEntry = thisNewGroup.findInChain(thisHash, thisEntry.tag());
          if (incomingEntry != null) {
            // there's already an entry w/ the same tag from the incoming TagMap
            // incoming entry clobbers the existing entry, so we're done
            thisBuckets[i] = thisNewGroup;

            // overlapping group - subtract one for clobbered existing entry
            added += thisNewGroupSize - 1;
          } else if (thisNewGroup.insertInChain(thisHash, thisEntry)) {
            // able to add thisEntry into the existing groups
            thisBuckets[i] = thisNewGroup;

            // non overlapping group - existing entry already counted
            added += thisNewGroupSize;
          } else {
            // unable to add into the existing groups
            thisBuckets[i] = new BucketGroup(thisHash, thisEntry, thisNewGroup);

            // non overlapping group - existing entry already counted
            added += thisNewGroupSize;
          }
        }
      } else if (thisBucket instanceof BucketGroup) {
        // This bucket is a BucketGroup, medium to hard case
        // If the other side is an entry, just normal insertion procedure - no cloning required
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        if (thatBucket instanceof Entry) {
          Entry thatEntry = (Entry) thatBucket;
          int thatHash = thatEntry.hash();

          if (thisGroup.replaceInChain(thatHash, thatEntry) != null) {
            // replaced existing entry no size change
          } else if (thisGroup.insertInChain(thatHash, thatEntry)) {
            added += 1;
          } else {
            thisBuckets[i] = new BucketGroup(thatHash, thatEntry, thisGroup);
            added += 1;
          }
        } else if (thatBucket instanceof BucketGroup) {
          // Most complicated case - need to walk that bucket group chain and update this chain
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          // Taking the easy / expensive way out for updating size
          int thisPrevGroupSize = thisGroup.sizeInChain();

          BucketGroup thisNewGroup = thisGroup.replaceOrInsertAllInChain(thatGroup);
          int thisNewGroupSize = thisNewGroup.sizeInChain();

          thisBuckets[i] = thisNewGroup;
          added += (thisNewGroupSize - thisPrevGroupSize);
        }
      }
    }
    return added;
  }

  /**
   * Visits every entry in {@code buckets}, which must hold no tombstones, as a {@link TagMap}'s and
   * a {@link HashStore}'s never do. {@link #forEachLive} skips them. This walk runs for every span
   * the serializer writes, so it stays the unrolled shape with no tombstone check.
   */
  static void forEach(Object[] buckets, Consumer<? super EntryReader> consumer) {
    for (int i = 0; i < buckets.length; ++i) {
      Object bucket = buckets[i];
      if (bucket instanceof Entry) {
        consumer.accept((Entry) bucket);
      } else if (bucket instanceof BucketGroup) {
        ((BucketGroup) bucket).forEachInChain(consumer);
      }
    }
  }

  /** {@link #forEach(Object[], Consumer)} with a context object for a non-capturing consumer. */
  static <T> void forEach(
      Object[] buckets, T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
    for (int i = 0; i < buckets.length; ++i) {
      Object bucket = buckets[i];
      if (bucket instanceof Entry) {
        consumer.accept(thisObj, (Entry) bucket);
      } else if (bucket instanceof BucketGroup) {
        ((BucketGroup) bucket).forEachInChain(thisObj, consumer);
      }
    }
  }

  /** {@link #forEach(Object[], Consumer)} with two context objects. */
  static <T, U> void forEach(
      Object[] buckets, T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    for (int i = 0; i < buckets.length; ++i) {
      Object bucket = buckets[i];
      if (bucket instanceof Entry) {
        consumer.accept(thisObj, otherObj, (Entry) bucket);
      } else if (bucket instanceof BucketGroup) {
        ((BucketGroup) bucket).forEachInChain(thisObj, otherObj, consumer);
      }
    }
  }

  /**
   * Visits every live entry in {@code buckets}, skipping tombstones, which only an id store holds.
   */
  static void forEachLive(Object[] buckets, Consumer<? super EntryReader> consumer) {
    for (int i = 0; i < buckets.length; ++i) {
      Object bucket = buckets[i];
      if (bucket instanceof Entry) {
        Entry entry = (Entry) bucket;
        if (!entry.tombstone) consumer.accept(entry);
      } else if (bucket instanceof BucketGroup) {
        for (BucketGroup group = (BucketGroup) bucket; group != null; group = group.prev) {
          for (int j = 0; j < BucketGroup.LEN; ++j) {
            Entry entry = group._entryAt(j);
            if (entry != null && !entry.tombstone) consumer.accept(entry);
          }
        }
      }
    }
  }

  /** {@link #forEachLive(Object[], Consumer)} with a context object. */
  static <T> void forEachLive(
      Object[] buckets, T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
    for (int i = 0; i < buckets.length; ++i) {
      Object bucket = buckets[i];
      if (bucket instanceof Entry) {
        Entry entry = (Entry) bucket;
        if (!entry.tombstone) consumer.accept(thisObj, entry);
      } else if (bucket instanceof BucketGroup) {
        for (BucketGroup group = (BucketGroup) bucket; group != null; group = group.prev) {
          for (int j = 0; j < BucketGroup.LEN; ++j) {
            Entry entry = group._entryAt(j);
            if (entry != null && !entry.tombstone) consumer.accept(thisObj, entry);
          }
        }
      }
    }
  }

  /** {@link #forEachLive(Object[], Consumer)} with two context objects. */
  static <T, U> void forEachLive(
      Object[] buckets, T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    for (int i = 0; i < buckets.length; ++i) {
      Object bucket = buckets[i];
      if (bucket instanceof Entry) {
        Entry entry = (Entry) bucket;
        if (!entry.tombstone) consumer.accept(thisObj, otherObj, entry);
      } else if (bucket instanceof BucketGroup) {
        for (BucketGroup group = (BucketGroup) bucket; group != null; group = group.prev) {
          for (int j = 0; j < BucketGroup.LEN; ++j) {
            Entry entry = group._entryAt(j);
            if (entry != null && !entry.tombstone) consumer.accept(thisObj, otherObj, entry);
          }
        }
      }
    }
  }

  /** Puts every live entry in {@code buckets} into {@code map}, by tag, as its object value. */
  static void fillMap(Object[] buckets, Map<? super String, Object> map) {
    forEachLive(buckets, map, (m, entry) -> m.put(entry.tag(), entry.objectValue()));
  }

  /** Puts every live entry in {@code buckets} into {@code map}, by tag, as its string value. */
  static void fillStringMap(Object[] buckets, Map<? super String, ? super String> map) {
    forEachLive(buckets, map, (m, entry) -> m.put(entry.tag(), entry.stringValue()));
  }

  /** The number of live entries, not counting tombstones. Tests only. */
  static int count(Object[] buckets) {
    int[] count = new int[1];
    forEachLive(buckets, count, (c, entry) -> c[0]++);
    return count[0];
  }

  /** Throws if an entry sits in the wrong bucket. Tests only. */
  static void checkBuckets(Object[] buckets) {
    // Decided to use if ( cond ) throw new IllegalStateException rather than assert
    // That was done to avoid the extra static initialization needed for an assertion
    // While that's probably an unnecessary optimization, this method is only called in tests

    Object[] thisBuckets = buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;
        int thisHash = thisEntry.hash();

        int expectedBucket = thisHash & (thisBuckets.length - 1);
        if (expectedBucket != i) {
          throw new IllegalStateException("incorrect bucket");
        }
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        for (BucketGroup curGroup = thisGroup; curGroup != null; curGroup = curGroup.prev) {
          for (int j = 0; j < BucketGroup.LEN; ++j) {
            Entry thisEntry = curGroup._entryAt(j);
            if (thisEntry == null) continue;

            int thisHash = thisEntry.hash();
            assert curGroup._hashAt(j) == thisHash;

            int expectedBucket = thisHash & (thisBuckets.length - 1);
            if (expectedBucket != i) {
              throw new IllegalStateException("incorrect bucket");
            }
          }
        }
      }
    }
  }

  /** The buckets' internal structure, for deep debugging. */
  static String toInternalString(Object[] buckets) {
    Object[] thisBuckets = buckets;

    StringBuilder ledger = new StringBuilder(128);
    for (int i = 0; i < thisBuckets.length; ++i) {
      ledger.append('[').append(i).append("] = ");

      Object thisBucket = thisBuckets[i];
      if (thisBucket == null) {
        ledger.append("null");
      } else if (thisBucket instanceof Entry) {
        ledger.append('{').append(thisBucket).append('}');
      } else if (thisBucket instanceof BucketGroup) {
        for (BucketGroup curGroup = (BucketGroup) thisBucket;
            curGroup != null;
            curGroup = curGroup.prev) {
          ledger.append(curGroup).append(" -> ");
        }
      }
      ledger.append('\n');
    }
    return ledger.toString();
  }

  /** Whether {@code buckets} holds no entry, checked bucket by bucket. */
  static boolean isEmpty(Object[] buckets) {
    Object[] thisBuckets = buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object curBucket = thisBuckets[i];

      if (curBucket instanceof Entry) {
        return false;
      } else if (curBucket instanceof BucketGroup) {
        BucketGroup curGroup = (BucketGroup) curBucket;
        if (!curGroup.isEmptyChain()) return false;
      }
    }

    return true;
  }

  /** Walks a bucket array's raw entries, tombstones included, for an iterator. */
  static class BucketWalker {
    private Object[] buckets;
    private int bucketIndex;
    private BucketGroup group;
    private int groupIndex;

    /** Starts over on {@code buckets}. */
    void reset(Object[] buckets) {
      this.buckets = buckets;
      this.bucketIndex = -1;
      this.group = null;
      this.groupIndex = 0;
    }

    /** The next raw entry, or null when the array is done. */
    final Entry nextEntry() {
      while (this.bucketIndex < this.buckets.length) {
        if (this.group != null) {
          for (++this.groupIndex; this.groupIndex < BucketGroup.LEN; ++this.groupIndex) {
            Entry tagEntry = this.group._entryAt(this.groupIndex);
            if (tagEntry != null) return tagEntry;
          }

          // done processing - that group, go to next group
          this.group = this.group.prev;
          this.groupIndex = -1;
        }

        // if the group is null, then we've finished the current bucket - so advance the bucket
        if (this.group == null) {
          for (++this.bucketIndex; this.bucketIndex < this.buckets.length; ++this.bucketIndex) {
            Object bucket = this.buckets[this.bucketIndex];

            if (bucket instanceof Entry) {
              return (Entry) bucket;
            } else if (bucket instanceof BucketGroup) {
              this.group = (BucketGroup) bucket;
              this.groupIndex = -1;

              break;
            }
          }
        }
      }

      return null;
    }
  }
}

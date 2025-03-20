package datadog.trace.api;

import datadog.trace.api.function.TriConsumer;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A super simple hash map designed for...
 *
 * <ul>
 *   <li>fast copy from one map to another
 *   <li>compatibility with Builder idioms
 *   <li>building small maps as fast as possible
 *   <li>storing primitives without boxing
 *   <li>minimal memory footprint
 * </ul>
 *
 * <p>This is mainly accomplished by using immutable entry objects that can reference an object or a
 * primitive. By using immutable entries, the entry objects can be shared between builders & maps
 * freely.
 *
 * <p>This map lacks some features of a regular java.util.Map...
 *
 * <ul>
 *   <li>Entry object mutation
 *   <li>size tracking - falls back to computeSize
 *   <li>manipulating Map through the entrySet() or values()
 * </ul>
 *
 * <p>Also lacks features designed for handling large maps...
 *
 * <ul>
 *   <li>bucket array expansion
 *   <li>adaptive collision
 * </ul>
 */

/*
 * For memory efficiency, TagMap uses a rather complicated bucket system.
 * <p>
 * When there is only a single Entry in a particular bucket, the Entry is stored into the bucket directly.
 * <p>
 * Because the Entry objects can be shared between multiple TagMaps, the Entry objects cannot contain
 * form a link list to handle collisions.
 * <p>
 * Instead when multiple entries collide in the same bucket, a BucketGroup is formed to hold multiple entries.
 * But a BucketGroup is only formed when a collision occurs to keep allocation low in the common case of no collisions.
 * <p>
 * For efficiency, BucketGroups are a fixed size, so when a BucketGroup fills up another BucketGroup is formed
 * to hold the additional Entry-s.  And the BucketGroup-s are connected via a linked list instead of the Entry-s.
 * <p>
 * This does introduce some inefficiencies when Entry-s are removed.
 * In the current system, given that removals are rare, BucketGroups are never consolidated.
 * However as a precaution if a BucketGroup becomes completely empty, then that BucketGroup will be
 * removed from the collision chain.
 */
public final class TagMap implements Map<String, Object>, Iterable<TagMap.Entry> {
  /** Immutable empty TagMap - similar to {@link Collections#emptyMap()} */
  public static final TagMap EMPTY = createEmpty();

  private static final TagMap createEmpty() {
    return new TagMap().freeze();
  }

  /** Creates a new TagMap.Builder */
  public static final Builder builder() {
    return new Builder();
  }

  /** Creates a new TagMap.Builder which handles <code>size</code> modifications before expansion */
  public static final Builder builder(int size) {
    return new Builder(size);
  }

  /** Creates a new mutable TagMap that contains the contents of <code>map</code> */
  public static final TagMap fromMap(Map<String, ?> map) {
    TagMap tagMap = new TagMap();
    tagMap.putAll(map);
    return tagMap;
  }

  /** Creates a new immutable TagMap that contains the contents <code>map</code> */
  public static final TagMap fromMapImmutable(Map<String, ?> map) {
    if (map.isEmpty()) {
      return TagMap.EMPTY;
    } else {
      return fromMap(map).freeze();
    }
  }

  private final Object[] buckets;
  private boolean frozen;

  public TagMap() {
    // needs to be a power of 2 for bucket masking calculation to work as intended
    this.buckets = new Object[1 << 4];
    this.frozen = false;
  }

  /** Used for inexpensive immutable */
  private TagMap(Object[] buckets) {
    this.buckets = buckets;
    this.frozen = true;
  }

  @Deprecated
  @Override
  public final int size() {
    return this.computeSize();
  }

  /**
   * Computes the size of the TagMap
   *
   * <p>computeSize is fast but is an O(n) operation.
   */
  public final int computeSize() {
    Object[] thisBuckets = this.buckets;

    int size = 0;
    for (int i = 0; i < thisBuckets.length; ++i) {
      Object curBucket = thisBuckets[i];

      if (curBucket instanceof Entry) {
        size += 1;
      } else if (curBucket instanceof BucketGroup) {
        BucketGroup curGroup = (BucketGroup) curBucket;
        size += curGroup.sizeInChain();
      }
    }
    return size;
  }

  @Deprecated
  @Override
  public boolean isEmpty() {
    return this.checkIfEmpty();
  }

  /**
   * Checks if TagMap is empty
   *
   * <p>checkIfEmpty is fast but is an O(n) operation.
   */
  public final boolean checkIfEmpty() {
    Object[] thisBuckets = this.buckets;

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

  @Override
  public boolean containsKey(Object key) {
    if (!(key instanceof String)) return false;

    return (this.getEntry((String) key) != null);
  }

  @Override
  public boolean containsValue(Object value) {
    for (Entry entry : this) {
      if (entry.objectValue().equals(value)) return true;
    }
    return false;
  }

  @Deprecated
  @Override
  public Set<String> keySet() {
    return new Keys(this);
  }

  @Deprecated
  @Override
  public Collection<Object> values() {
    return new Values(this);
  }

  @Deprecated
  @Override
  public Set<java.util.Map.Entry<String, Object>> entrySet() {
    return new Entries(this);
  }

  @Deprecated
  @Override
  public final Object get(Object tag) {
    if (!(tag instanceof String)) return null;

    return this.getObject((String) tag);
  }

  /** Provides the corresponding entry value as an Object - boxing if necessary */
  public final Object getObject(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? null : entry.objectValue();
  }

  /** Provides the corresponding entry value as a String - calling toString if necessary */
  public final String getString(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? null : entry.stringValue();
  }

  public final boolean getBoolean(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? false : entry.booleanValue();
  }

  public final int getInt(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? 0 : entry.intValue();
  }

  public final long getLong(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? 0L : entry.longValue();
  }

  public final float getFloat(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? 0F : entry.floatValue();
  }

  public final double getDouble(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? 0D : entry.doubleValue();
  }

  /**
   * Provides the corresponding Entry object - preferable if the Entry needs to have its type
   * checked
   */
  public final Entry getEntry(String tag) {
    Object[] thisBuckets = this.buckets;

    int hash = _hash(tag);
    int bucketIndex = hash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    if (bucket == null) {
      return null;
    } else if (bucket instanceof Entry) {
      Entry tagEntry = (Entry) bucket;
      if (tagEntry.matches(tag)) return tagEntry;
    } else if (bucket instanceof BucketGroup) {
      BucketGroup lastGroup = (BucketGroup) bucket;

      Entry tagEntry = lastGroup.findInChain(hash, tag);
      if (tagEntry != null) return tagEntry;
    }
    return null;
  }

  @Deprecated
  public final Object put(String tag, Object value) {
    TagMap.Entry entry = this.putEntry(Entry.newAnyEntry(tag, value));
    return entry == null ? null : entry.objectValue();
  }

  /**
   * Similar to {@link Map#put(Object, Object)}, but returns the prior Entry rather than the prior
   * value
   *
   * <p>Preferred to put because avoids having to box prior primitive value
   */
  public final Entry set(String tag, Object value) {
    return this.putEntry(Entry.newAnyEntry(tag, value));
  }

  /**
   * Similar to {@link TagMap#set(String, Object)} but more efficient when working with
   * CharSequences and Strings. Depending on this situation, this methods avoids having to do type
   * resolution later on
   */
  public final Entry set(String tag, CharSequence value) {
    return this.putEntry(Entry.newObjectEntry(tag, value));
  }

  public final Entry set(String tag, boolean value) {
    return this.putEntry(Entry.newBooleanEntry(tag, value));
  }

  public final Entry set(String tag, int value) {
    return this.putEntry(Entry.newIntEntry(tag, value));
  }

  public final Entry set(String tag, long value) {
    return this.putEntry(Entry.newLongEntry(tag, value));
  }

  public final Entry set(String tag, float value) {
    return this.putEntry(Entry.newFloatEntry(tag, value));
  }

  public final Entry set(String tag, double value) {
    return this.putEntry(Entry.newDoubleEntry(tag, value));
  }

  /**
   * TagMap specific method that places an Entry directly into the TagMap avoiding needing to
   * allocate a new Entry object
   */
  public final Entry putEntry(Entry newEntry) {
    this.checkWriteAccess();

    Object[] thisBuckets = this.buckets;

    int newHash = newEntry.hash();
    int bucketIndex = newHash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    if (bucket == null) {
      thisBuckets[bucketIndex] = newEntry;

      return null;
    } else if (bucket instanceof Entry) {
      Entry existingEntry = (Entry) bucket;
      if (existingEntry.matches(newEntry.tag)) {
        thisBuckets[bucketIndex] = newEntry;

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
        return containingGroup._replace(newHash, newEntry);
      }

      if (!lastGroup.insertInChain(newHash, newEntry)) {
        thisBuckets[bucketIndex] = new BucketGroup(newHash, newEntry, lastGroup);
      }
      return null;
    }
    return null;
  }

  public final void putAll(Iterable<? extends Entry> entries) {
    this.checkWriteAccess();

    for (Entry tagEntry : entries) {
      if (tagEntry.isRemoval()) {
        this.remove(tagEntry.tag);
      } else {
        this.putEntry(tagEntry);
      }
    }
  }

  public final void putAll(TagMap.Builder builder) {
    putAll(builder.entries, builder.nextPos);
  }

  private final void putAll(Entry[] tagEntries, int size) {
    for (int i = 0; i < size && i < tagEntries.length; ++i) {
      Entry tagEntry = tagEntries[i];

      if (tagEntry.isRemoval()) {
        this.remove(tagEntry.tag);
      } else {
        this.putEntry(tagEntry);
      }
    }
  }

  /**
   * Similar to {@link Map#putAll(Map)} but optimized to quickly copy from TagMap to another
   *
   * <p>This method takes advantage of the consistent Map layout to optimize the handling of each
   * bucket. And similar to {@link TagMap#putEntry(Entry)} this method shares Entry objects from the
   * source TagMap
   */
  public final void putAll(TagMap that) {
    this.checkWriteAccess();

    Object[] thisBuckets = this.buckets;
    Object[] thatBuckets = that.buckets;

    // Since TagMap-s don't support expansion, buckets are perfectly aligned
    for (int i = 0; i < thisBuckets.length && i < thatBuckets.length; ++i) {
      Object thatBucket = thatBuckets[i];

      // nothing in the other hash, just skip this bucket
      if (thatBucket == null) continue;

      Object thisBucket = thisBuckets[i];

      if (thisBucket == null) {
        // This bucket is null, easy case
        // Either copy over the sole entry or clone the BucketGroup chain

        if (thatBucket instanceof Entry) {
          thisBuckets[i] = thatBucket;
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          thisBuckets[i] = thatGroup.cloneChain();
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
          } else {
            thisBuckets[i] =
                new BucketGroup(
                    thisHash, thisEntry,
                    thatHash, thatEntry);
          }
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          // Clone the other group, then place this entry into that group
          BucketGroup thisNewGroup = thatGroup.cloneChain();

          Entry incomingEntry = thisNewGroup.findInChain(thisHash, thisEntry.tag());
          if (incomingEntry != null) {
            // there's already an entry w/ the same tag from the incoming TagMap - done
            thisBuckets[i] = thisNewGroup;
          } else if (thisNewGroup.insertInChain(thisHash, thisEntry)) {
            // able to add thisEntry into the existing groups
            thisBuckets[i] = thisNewGroup;
          } else {
            // unable to add into the existing groups
            thisBuckets[i] = new BucketGroup(thisHash, thisEntry, thisNewGroup);
          }
        }
      } else if (thisBucket instanceof BucketGroup) {
        // This bucket is a BucketGroup, medium to hard case
        // If the other side is an entry, just normal insertion procedure - no cloning required
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        if (thatBucket instanceof Entry) {
          Entry thatEntry = (Entry) thatBucket;
          int thatHash = thatEntry.hash();

          if (!thisGroup.replaceOrInsertInChain(thatHash, thatEntry)) {
            thisBuckets[i] = new BucketGroup(thatHash, thatEntry, thisGroup);
          }
        } else if (thatBucket instanceof BucketGroup) {
          // Most complicated case - need to walk that bucket group chain and update this chain
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          thisBuckets[i] = thisGroup.replaceOrInsertAllInChain(thatGroup);
        }
      }
    }
  }

  public final void putAll(Map<? extends String, ? extends Object> map) {
    this.checkWriteAccess();

    if (map instanceof TagMap) {
      this.putAll((TagMap) map);
    } else {
      for (Map.Entry<? extends String, ?> entry : map.entrySet()) {
        this.put(entry.getKey(), entry.getValue());
      }
    }
  }

  public final void fillMap(Map<? super String, Object> map) {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        map.put(thisEntry.tag, thisEntry.objectValue());
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.fillMapFromChain(map);
      }
    }
  }

  public final void fillStringMap(Map<? super String, ? super String> stringMap) {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        stringMap.put(thisEntry.tag, thisEntry.stringValue());
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.fillStringMapFromChain(stringMap);
      }
    }
  }

  @Deprecated
  @Override
  public final Object remove(Object tag) {
    if (!(tag instanceof String)) return null;

    Entry entry = this.removeEntry((String) tag);
    return entry == null ? null : entry.objectValue();
  }

  /**
   * Similar to {@link Map#remove(Object)} but returns the prior Entry object rather than the prior
   * value This is preferred because it avoids boxing a prior primitive value
   */
  public final Entry removeEntry(String tag) {
    this.checkWriteAccess();

    Object[] thisBuckets = this.buckets;

    int hash = _hash(tag);
    int bucketIndex = hash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    // null bucket case - do nothing
    if (bucket instanceof Entry) {
      Entry existingEntry = (Entry) bucket;
      if (existingEntry.matches(tag)) {
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
        this.buckets[bucketIndex] = lastGroup.removeGroupInChain(containingGroup);
      }

      return existingEntry;
    }
    return null;
  }

  /** Returns a mutable copy of this TagMap */
  public final TagMap copy() {
    TagMap copy = new TagMap();
    copy.putAll(this);
    return copy;
  }

  /**
   * Returns an immutable copy of this TagMap This method is more efficient than <code>
   * map.copy().freeze()</code> when called on an immutable TagMap
   */
  public final TagMap immutableCopy() {
    if (this.frozen) {
      return this;
    } else {
      return this.copy().freeze();
    }
  }

  public final TagMap immutable() {
    // specialized constructor, freezes map immediately
    return new TagMap(this.buckets);
  }

  /**
   * Provides an Iterator over the Entry-s of the TagMap Equivalent to <code>entrySet().iterator()
   * </code>, but with less allocation
   */
  @Override
  public final Iterator<Entry> iterator() {
    return new EntryIterator(this);
  }

  public final Stream<Entry> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  /**
   * Visits each Entry in this TagMap This method is more efficient than {@link TagMap#iterator()}
   */
  public final void forEach(Consumer<? super TagMap.Entry> consumer) {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        consumer.accept(thisEntry);
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.forEachInChain(consumer);
      }
    }
  }

  /**
   * Version of forEach that takes an extra context object that is passed as the first argument to
   * the consumer
   *
   * <p>The intention is to use this method to avoid using a capturing lambda
   */
  public final <T> void forEach(T thisObj, BiConsumer<T, ? super TagMap.Entry> consumer) {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        consumer.accept(thisObj, thisEntry);
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.forEachInChain(thisObj, consumer);
      }
    }
  }

  /**
   * Version of forEach that takes two extra context objects that are passed as the first two
   * argument to the consumer
   *
   * <p>The intention is to use this method to avoid using a capturing lambda
   */
  public final <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.Entry> consumer) {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        consumer.accept(thisObj, otherObj, thisEntry);
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.forEachInChain(thisObj, otherObj, consumer);
      }
    }
  }

  /** Clears the TagMap */
  public final void clear() {
    this.checkWriteAccess();

    Arrays.fill(this.buckets, null);
  }

  /** Freeze the TagMap preventing further modification - returns <code>this</code> TagMap */
  public final TagMap freeze() {
    this.frozen = true;

    return this;
  }

  /** Indicates if this map is frozen */
  public boolean isFrozen() {
    return this.frozen;
  }

  /** Checks if the TagMap is writable - if not throws {@link IllegalStateException} */
  public final void checkWriteAccess() {
    if (this.frozen) throw new IllegalStateException("TagMap frozen");
  }

  //  final void check() {
  //    Object[] thisBuckets = this.buckets;
  //
  //    for ( int i = 0; i < thisBuckets.length; ++i ) {
  //      Object thisBucket = thisBuckets[i];
  //
  //      if ( thisBucket instanceof Entry ) {
  //        Entry thisEntry = (Entry)thisBucket;
  //        int thisHash = thisEntry.hash();
  //
  //        int expectedBucket = thisHash & (thisBuckets.length - 1);
  //        assert expectedBucket == i;
  //      } else if ( thisBucket instanceof BucketGroup ) {
  //        BucketGroup thisGroup = (BucketGroup)thisBucket;
  //
  //        for ( BucketGroup curGroup = thisGroup;
  //          curGroup != null;
  //          curGroup = curGroup.prev )
  //        {
  //          for ( int j = 0; j < BucketGroup.LEN; ++j ) {
  //            Entry thisEntry = curGroup._entryAt(i);
  //            if ( thisEntry == null ) continue;
  //
  //            int thisHash = thisEntry.hash();
  //            assert curGroup._hashAt(i) == thisHash;
  //
  //            int expectedBucket = thisHash & (thisBuckets.length - 1);
  //            assert expectedBucket == i;
  //          }
  //        }
  //      }
  //    }
  //  }

  @Override
  public final String toString() {
    return toPrettyString();
  }

  /**
   * Standard toString implementation - output is similar to {@link java.util.HashMap#toString()}
   */
  final String toPrettyString() {
    boolean first = true;

    StringBuilder builder = new StringBuilder(128);
    builder.append('{');
    for (Entry entry : this) {
      if (first) {
        first = false;
      } else {
        builder.append(",");
      }

      builder.append(entry.tag).append('=').append(entry.stringValue());
    }
    builder.append('}');
    return builder.toString();
  }

  /**
   * toString that more visibility into the internal structure of TagMap - primarily for deep
   * debugging
   */
  final String toInternalString() {
    Object[] thisBuckets = this.buckets;

    StringBuilder builder = new StringBuilder(128);
    for (int i = 0; i < thisBuckets.length; ++i) {
      builder.append('[').append(i).append("] = ");

      Object thisBucket = thisBuckets[i];
      if (thisBucket == null) {
        builder.append("null");
      } else if (thisBucket instanceof Entry) {
        builder.append('{').append(thisBucket).append('}');
      } else if (thisBucket instanceof BucketGroup) {
        for (BucketGroup curGroup = (BucketGroup) thisBucket;
            curGroup != null;
            curGroup = curGroup.prev) {
          builder.append(curGroup).append(" -> ");
        }
      }
      builder.append('\n');
    }
    return builder.toString();
  }

  static final int _hash(String tag) {
    int hash = tag.hashCode();
    return hash == 0 ? 0xDD06 : hash ^ (hash >>> 16);
  }

  public static final class Entry implements Map.Entry<String, Object> {
    /*
     * Special value used to record removals in the builder
     * Removal entries are never stored in the TagMap itself
     */
    private static final byte REMOVED = -1;

    /*
     * Special value used for Objects that haven't been type checked yet.
     * These objects might be primitive box objects.
     */
    public static final byte ANY = 0;
    public static final byte OBJECT = 1;

    /*
     * Non-numeric primitive types
     */
    public static final byte BOOLEAN = 2;
    public static final byte CHAR = 3;

    /*
     * Numeric constants - deliberately arranged to allow for checking by using type >= BYTE
     */
    public static final byte BYTE = 4;
    public static final byte SHORT = 5;
    public static final byte INT = 6;
    public static final byte LONG = 7;
    public static final byte FLOAT = 8;
    public static final byte DOUBLE = 9;

    static final Entry newAnyEntry(String tag, Object value) {
      // DQH - To keep entry creation (e.g. map changes) as fast as possible,
      // the entry construction is kept as simple as possible.

      // Prior versions of this code did type detection on value to
      // recognize box types but that proved expensive.  So now,
      // the type is recorded as an ANY which is an indicator to do
      // type detection later if need be.
      return new Entry(tag, ANY, 0L, value);
    }

    static final Entry newObjectEntry(String tag, Object value) {
      return new Entry(tag, OBJECT, 0, value);
    }

    static final Entry newBooleanEntry(String tag, boolean value) {
      return new Entry(tag, BOOLEAN, boolean2Prim(value), Boolean.valueOf(value));
    }

    static final Entry newBooleanEntry(String tag, Boolean box) {
      return new Entry(tag, BOOLEAN, boolean2Prim(box.booleanValue()), box);
    }

    static final Entry newIntEntry(String tag, int value) {
      return new Entry(tag, INT, int2Prim(value), null);
    }

    static final Entry newIntEntry(String tag, Integer box) {
      return new Entry(tag, INT, int2Prim(box.intValue()), box);
    }

    static final Entry newLongEntry(String tag, long value) {
      return new Entry(tag, LONG, long2Prim(value), null);
    }

    static final Entry newLongEntry(String tag, Long box) {
      return new Entry(tag, LONG, long2Prim(box.longValue()), box);
    }

    static final Entry newFloatEntry(String tag, float value) {
      return new Entry(tag, FLOAT, float2Prim(value), null);
    }

    static final Entry newFloatEntry(String tag, Float box) {
      return new Entry(tag, FLOAT, float2Prim(box.floatValue()), box);
    }

    static final Entry newDoubleEntry(String tag, double value) {
      return new Entry(tag, DOUBLE, double2Prim(value), null);
    }

    static final Entry newDoubleEntry(String tag, Double box) {
      return new Entry(tag, DOUBLE, double2Prim(box.doubleValue()), box);
    }

    static final Entry newRemovalEntry(String tag) {
      return new Entry(tag, REMOVED, 0, null);
    }

    final String tag;

    /*
     * hash is stored in line for fast handling of Entry-s coming another Tag
     * However, hash is lazily computed using the same trick as {@link java.lang.String}.
     */
    int hash;

    // To optimize construction of Entry around boxed primitives and Object entries,
    // no type checks are done during construction.
    // Any Object entries are initially marked as type ANY, prim set to 0, and the Object put into
    // obj
    // If an ANY entry is later type checked or request as a primitive, then the ANY will be
    // resolved
    // to the correct type.

    // From the outside perspective, this object remains functionally immutable.
    // However, internally, it is important to remember that this type must be thread safe.
    // That includes multiple threads racing to resolve an ANY entry at the same time.

    // Type and prim cannot use the same trick as hash because during ANY resolution the order of
    // writes is important
    volatile byte type;
    volatile long prim;
    volatile Object obj;

    volatile String strCache = null;

    private Entry(String tag, byte type, long prim, Object obj) {
      this.tag = tag;
      this.hash = 0; // lazily computed
      this.type = type;
      this.prim = prim;
      this.obj = obj;
    }

    public final String tag() {
      return this.tag;
    }

    int hash() {
      // If value of hash read in this thread is zero, then hash is computed.
      // hash is not held as a volatile, since this computation can safely be repeated as any time
      int hash = this.hash;
      if (hash != 0) return hash;

      hash = _hash(this.tag);
      this.hash = hash;
      return hash;
    }

    public final byte type() {
      return this.resolveAny();
    }

    public final boolean is(byte type) {
      byte curType = this.type;
      if (curType == type) {
        return true;
      } else if (curType != ANY) {
        return false;
      } else {
        return (this.resolveAny() == type);
      }
    }

    public final boolean isNumericPrimitive() {
      byte curType = this.type;
      if (_isNumericPrimitive(curType)) {
        return true;
      } else if (curType != ANY) {
        return false;
      } else {
        return _isNumericPrimitive(this.resolveAny());
      }
    }

    public final boolean isNumber() {
      byte curType = this.type;
      return _isNumericPrimitive(curType) || (this.obj instanceof Number);
    }

    private static final boolean _isNumericPrimitive(byte type) {
      return (type >= BYTE);
    }

    private final byte resolveAny() {
      byte curType = this.type;
      if (curType != ANY) return curType;

      Object value = this.obj;
      long prim;
      byte resolvedType;

      if (value instanceof Boolean) {
        Boolean boolValue = (Boolean) value;
        prim = boolean2Prim(boolValue);
        resolvedType = BOOLEAN;
      } else if (value instanceof Integer) {
        Integer intValue = (Integer) value;
        prim = int2Prim(intValue);
        resolvedType = INT;
      } else if (value instanceof Long) {
        Long longValue = (Long) value;
        prim = long2Prim(longValue);
        resolvedType = LONG;
      } else if (value instanceof Float) {
        Float floatValue = (Float) value;
        prim = float2Prim(floatValue);
        resolvedType = FLOAT;
      } else if (value instanceof Double) {
        Double doubleValue = (Double) value;
        prim = double2Prim(doubleValue);
        resolvedType = DOUBLE;
      } else {
        prim = 0;
        resolvedType = OBJECT;
      }

      this._setPrim(resolvedType, prim);

      return resolvedType;
    }

    private void _setPrim(byte type, long prim) {
      // Order is important here, the contract is that prim must be set properly *before*
      // type is set to a non-object type

      this.prim = prim;
      this.type = type;
    }

    public final boolean isObject() {
      return this.is(OBJECT);
    }

    public final boolean isRemoval() {
      return this.is(REMOVED);
    }

    public final boolean matches(String tag) {
      return this.tag.equals(tag);
    }

    public final Object objectValue() {
      if (this.obj != null) {
        return this.obj;
      }

      // This code doesn't need to handle ANY-s.
      // An entry that starts as an ANY will always have this.obj set
      switch (this.type) {
        case BOOLEAN:
          this.obj = prim2Boolean(this.prim);
          break;

        case INT:
          // Maybe use a wider cache that handles response code???
          this.obj = prim2Int(this.prim);
          break;

        case LONG:
          this.obj = prim2Long(this.prim);
          break;

        case FLOAT:
          this.obj = prim2Float(this.prim);
          break;

        case DOUBLE:
          this.obj = prim2Double(this.prim);
          break;
      }

      if (this.is(REMOVED)) {
        return null;
      }

      return this.obj;
    }

    public final boolean booleanValue() {
      byte type = this.type;

      if (type == BOOLEAN) {
        return prim2Boolean(this.prim);
      } else if (type == ANY && this.obj instanceof Boolean) {
        boolean boolValue = (Boolean) this.obj;
        this._setPrim(BOOLEAN, boolean2Prim(boolValue));
        return boolValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.prim;

      switch (resolvedType) {
        case INT:
          return prim2Int(prim) != 0;

        case LONG:
          return prim2Long(prim) != 0L;

        case FLOAT:
          return prim2Float(prim) != 0F;

        case DOUBLE:
          return prim2Double(prim) != 0D;

        case OBJECT:
          return (this.obj != null);

        case REMOVED:
          return false;
      }

      return false;
    }

    public final int intValue() {
      byte type = this.type;

      if (type == INT) {
        return prim2Int(this.prim);
      } else if (type == ANY && this.obj instanceof Integer) {
        int intValue = (Integer) this.obj;
        this._setPrim(INT, int2Prim(intValue));
        return intValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.prim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1 : 0;

        case LONG:
          return (int) prim2Long(prim);

        case FLOAT:
          return (int) prim2Float(prim);

        case DOUBLE:
          return (int) prim2Double(prim);

        case OBJECT:
          return 0;

        case REMOVED:
          return 0;
      }

      return 0;
    }

    public final long longValue() {
      byte type = this.type;

      if (type == LONG) {
        return prim2Long(this.prim);
      } else if (type == ANY && this.obj instanceof Long) {
        long longValue = (Long) this.obj;
        this._setPrim(LONG, long2Prim(longValue));
        return longValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.prim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1L : 0L;

        case INT:
          return (long) prim2Int(prim);

        case FLOAT:
          return (long) prim2Float(prim);

        case DOUBLE:
          return (long) prim2Double(prim);

        case OBJECT:
          return 0;

        case REMOVED:
          return 0;
      }

      return 0;
    }

    public final float floatValue() {
      byte type = this.type;

      if (type == FLOAT) {
        return prim2Float(this.prim);
      } else if (type == ANY && this.obj instanceof Float) {
        float floatValue = (Float) this.obj;
        this._setPrim(FLOAT, float2Prim(floatValue));
        return floatValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.prim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1F : 0F;

        case INT:
          return (float) prim2Int(prim);

        case LONG:
          return (float) prim2Long(prim);

        case DOUBLE:
          return (float) prim2Double(prim);

        case OBJECT:
          return 0F;

        case REMOVED:
          return 0F;
      }

      return 0F;
    }

    public final double doubleValue() {
      byte type = this.type;

      if (type == DOUBLE) {
        return prim2Double(this.prim);
      } else if (type == ANY && this.obj instanceof Double) {
        double doubleValue = (Double) this.obj;
        this._setPrim(DOUBLE, double2Prim(doubleValue));
        return doubleValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.prim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1D : 0D;

        case INT:
          return (double) prim2Int(prim);

        case LONG:
          return (double) prim2Long(prim);

        case FLOAT:
          return (double) prim2Float(prim);

        case OBJECT:
          return 0D;

        case REMOVED:
          return 0D;
      }

      return 0D;
    }

    public final String stringValue() {
      String strCache = this.strCache;
      if (strCache != null) {
        return strCache;
      }

      String computeStr = this.computeStringValue();
      this.strCache = computeStr;
      return computeStr;
    }

    private final String computeStringValue() {
      // Could do type resolution here,
      // but decided to just fallback to this.obj.toString() for ANY case
      switch (this.type) {
        case BOOLEAN:
          return Boolean.toString(prim2Boolean(this.prim));

        case INT:
          return Integer.toString(prim2Int(this.prim));

        case LONG:
          return Long.toString(prim2Long(this.prim));

        case FLOAT:
          return Float.toString(prim2Float(this.prim));

        case DOUBLE:
          return Double.toString(prim2Double(this.prim));

        case REMOVED:
          return null;

        case OBJECT:
        case ANY:
          return this.obj.toString();
      }

      return null;
    }

    @Override
    public final String toString() {
      return this.tag() + '=' + this.stringValue();
    }

    @Deprecated
    @Override
    public String getKey() {
      return this.tag();
    }

    @Deprecated
    @Override
    public Object getValue() {
      return this.objectValue();
    }

    @Deprecated
    @Override
    public Object setValue(Object value) {
      throw new UnsupportedOperationException();
    }

    private static final long boolean2Prim(boolean value) {
      return value ? 1L : 0L;
    }

    private static final boolean prim2Boolean(long prim) {
      return (prim != 0L);
    }

    private static final long int2Prim(int value) {
      return (long) value;
    }

    private static final int prim2Int(long prim) {
      return (int) prim;
    }

    private static final long long2Prim(long value) {
      return value;
    }

    private static final long prim2Long(long prim) {
      return prim;
    }

    private static final long float2Prim(float value) {
      return (long) Float.floatToIntBits(value);
    }

    private static final float prim2Float(long prim) {
      return Float.intBitsToFloat((int) prim);
    }

    private static final long double2Prim(double value) {
      return Double.doubleToRawLongBits(value);
    }

    private static final double prim2Double(long prim) {
      return Double.longBitsToDouble(prim);
    }
  }

  public static final class Builder implements Iterable<Entry> {
    private Entry[] entries;
    private int nextPos = 0;

    private Builder() {
      this(8);
    }

    private Builder(int size) {
      this.entries = new Entry[size];
    }

    public final boolean isDefinitelyEmpty() {
      return (this.nextPos == 0);
    }

    /**
     * Provides the estimated size of the map created by the builder Doesn't account for overwritten
     * entries or entry removal
     *
     * @return
     */
    public final int estimateSize() {
      return this.nextPos;
    }

    public final Builder put(String tag, Object value) {
      return this.put(Entry.newAnyEntry(tag, value));
    }

    public final Builder put(String tag, CharSequence value) {
      return this.put(Entry.newObjectEntry(tag, value));
    }

    public final Builder put(String tag, boolean value) {
      return this.put(Entry.newBooleanEntry(tag, value));
    }

    public final Builder put(String tag, int value) {
      return this.put(Entry.newIntEntry(tag, value));
    }

    public final Builder put(String tag, long value) {
      return this.put(Entry.newLongEntry(tag, value));
    }

    public final Builder put(String tag, float value) {
      return this.put(Entry.newFloatEntry(tag, value));
    }

    public final Builder put(String tag, double value) {
      return this.put(Entry.newDoubleEntry(tag, value));
    }

    public final Builder remove(String tag) {
      return this.put(Entry.newRemovalEntry(tag));
    }

    public final Builder put(Entry entry) {
      if (this.nextPos >= this.entries.length) {
        this.entries = Arrays.copyOf(this.entries, this.entries.length << 1);
      }

      this.entries[this.nextPos++] = entry;
      return this;
    }

    public final void reset() {
      Arrays.fill(this.entries, null);
      this.nextPos = 0;
    }

    @Override
    public final Iterator<Entry> iterator() {
      return new BuilderIterator(this.entries, this.nextPos);
    }

    public TagMap build() {
      TagMap map = new TagMap();
      if (this.nextPos != 0) map.putAll(this.entries, this.nextPos);
      return map;
    }

    public TagMap buildImmutable() {
      if (this.nextPos == 0) {
        return TagMap.EMPTY;
      } else {
        return this.build().freeze();
      }
    }
  }

  private static final class BuilderIterator implements Iterator<Entry> {
    private final Entry[] entries;
    private final int size;

    private int pos;

    BuilderIterator(Entry[] entries, int size) {
      this.entries = entries;
      this.size = size;

      this.pos = -1;
    }

    @Override
    public final boolean hasNext() {
      return (this.pos + 1 < this.size);
    }

    @Override
    public Entry next() {
      if (!this.hasNext()) throw new NoSuchElementException("no next");

      return this.entries[++this.pos];
    }
  }

  private abstract static class MapIterator<T> implements Iterator<T> {
    private final Object[] buckets;

    private Entry nextEntry;

    private int bucketIndex = -1;

    private BucketGroup group = null;
    private int groupIndex = 0;

    MapIterator(TagMap map) {
      this.buckets = map.buckets;
    }

    @Override
    public boolean hasNext() {
      if (this.nextEntry != null) return true;

      while (this.bucketIndex < this.buckets.length) {
        this.nextEntry = this.advance();
        if (this.nextEntry != null) return true;
      }

      return false;
    }

    Entry nextEntry() {
      if (this.nextEntry != null) {
        Entry nextEntry = this.nextEntry;
        this.nextEntry = null;
        return nextEntry;
      }

      if (this.hasNext()) {
        return this.nextEntry;
      } else {
        throw new NoSuchElementException();
      }
    }

    private final Entry advance() {
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
      ;

      return null;
    }
  }

  static final class EntryIterator extends MapIterator<Entry> {
    EntryIterator(TagMap map) {
      super(map);
    }

    @Override
    public Entry next() {
      return this.nextEntry();
    }
  }

  /**
   * BucketGroup is a compromise for performance over a linked list or array
   *
   * <ul>
   *   <li>linked list - would prevent TagEntry-s from being immutable and would limit sharing
   *       opportunities
   *   <li>arrays - wouldn't be able to store hashes close together
   *   <li>parallel arrays (one for hashes & another for entries) would require more allocation
   * </ul>
   */
  static final class BucketGroup {
    static final int LEN = 4;

    /*
     * To make search operations on BucketGroups fast, the hashes for each entry are held inside
     * the BucketGroup.  This avoids pointer chasing to inspect each Entry object.
     * <p>
     * As a further optimization, the hashes are deliberately placed next to each other.
     * The intention is that the hashes will all end up in the same cache line, so loading
     * one hash effectively loads the others for free.
     * <p>
     * A hash of zero indicates an available slot, the hashes passed to BucketGroup must be "adjusted"
     * hashes which can never be zero.  The zero handling is done by TagMap#_hash.
     */
    int hash0 = 0;
    int hash1 = 0;
    int hash2 = 0;
    int hash3 = 0;

    Entry entry0 = null;
    Entry entry1 = null;
    Entry entry2 = null;
    Entry entry3 = null;

    BucketGroup prev = null;

    BucketGroup() {}

    /** New group with an entry pointing to existing BucketGroup */
    BucketGroup(int hash0, Entry entry0, BucketGroup prev) {
      this.hash0 = hash0;
      this.entry0 = entry0;

      this.prev = prev;
    }

    /** New group composed of two entries */
    BucketGroup(int hash0, Entry entry0, int hash1, Entry entry1) {
      this.hash0 = hash0;
      this.entry0 = entry0;

      this.hash1 = hash1;
      this.entry1 = entry1;
    }

    /** New group composed of 4 entries - used for cloning */
    BucketGroup(
        int hash0,
        Entry entry0,
        int hash1,
        Entry entry1,
        int hash2,
        Entry entry2,
        int hash3,
        Entry entry3) {
      this.hash0 = hash0;
      this.entry0 = entry0;

      this.hash1 = hash1;
      this.entry1 = entry1;

      this.hash2 = hash2;
      this.entry2 = entry2;

      this.hash3 = hash3;
      this.entry3 = entry3;
    }

    Entry _entryAt(int index) {
      switch (index) {
        case 0:
          return this.entry0;

        case 1:
          return this.entry1;

        case 2:
          return this.entry2;

        case 3:
          return this.entry3;
      }

      return null;
    }

    int _hashAt(int index) {
      switch (index) {
        case 0:
          return this.hash0;

        case 1:
          return this.hash1;

        case 2:
          return this.hash2;

        case 3:
          return this.hash3;
      }

      return 0;
    }

    int sizeInChain() {
      int size = 0;
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        size += curGroup._size();
      }
      return size;
    }

    int _size() {
      return (this.hash0 == 0 ? 0 : 1)
          + (this.hash1 == 0 ? 0 : 1)
          + (this.hash2 == 0 ? 0 : 1)
          + (this.hash3 == 0 ? 0 : 1);
    }

    boolean isEmptyChain() {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        if (!curGroup._isEmpty()) return false;
      }
      return true;
    }

    boolean _isEmpty() {
      return (this.hash0 | this.hash1 | this.hash2 | this.hash3) == 0;
    }

    BucketGroup findContainingGroupInChain(int hash, String tag) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        if (curGroup._find(hash, tag) != null) return curGroup;
      }
      return null;
    }

    Entry findInChain(int hash, String tag) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        Entry curEntry = curGroup._find(hash, tag);
        if (curEntry != null) return curEntry;
      }
      return null;
    }

    Entry _find(int hash, String tag) {
      // if ( this._mayContain(hash) ) return null;

      if (this.hash0 == hash && this.entry0.matches(tag)) {
        return this.entry0;
      } else if (this.hash1 == hash && this.entry1.matches(tag)) {
        return this.entry1;
      } else if (this.hash2 == hash && this.entry2.matches(tag)) {
        return this.entry2;
      } else if (this.hash3 == hash && this.entry3.matches(tag)) {
        return this.entry3;
      }
      return null;
    }

    BucketGroup replaceOrInsertAllInChain(BucketGroup thatHeadGroup) {
      BucketGroup thisOrigHeadGroup = this;
      BucketGroup thisNewestHeadGroup = thisOrigHeadGroup;

      for (BucketGroup thatCurGroup = thatHeadGroup;
          thatCurGroup != null;
          thatCurGroup = thatCurGroup.prev) {
        // First phase - tries to replace or insert each entry in the existing bucket chain
        // Only need to search the original groups for replacements
        // The whole chain is eligible for insertions
        boolean handled0 =
            (thatCurGroup.hash0 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash0, thatCurGroup.entry0)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash0, thatCurGroup.entry0);

        boolean handled1 =
            (thatCurGroup.hash1 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash1, thatCurGroup.entry1)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash1, thatCurGroup.entry1);

        boolean handled2 =
            (thatCurGroup.hash2 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash2, thatCurGroup.entry2)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash2, thatCurGroup.entry2);

        boolean handled3 =
            (thatCurGroup.hash3 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash3, thatCurGroup.entry3)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash3, thatCurGroup.entry3);

        // Second phase - takes any entries that weren't handled by phase 1 and puts them
        // into a new BucketGroup.  Since BucketGroups are fixed size, we know that the
        // left over entries from one BucketGroup will fit in the new BucketGroup.
        if (!handled0 || !handled1 || !handled2 || !handled3) {
          // Rather than calling insert one time per entry
          // Exploiting the fact that the new group is known to be empty
          // And that BucketGroups are allowed to have holes in them (to allow for removal),
          // so each unhandled entry from the source group is simply placed in
          // the same slot in the new group
          BucketGroup thisNewHashGroup = new BucketGroup();
          if (!handled0) {
            thisNewHashGroup.hash0 = thatCurGroup.hash0;
            thisNewHashGroup.entry0 = thatCurGroup.entry0;
          }
          if (!handled1) {
            thisNewHashGroup.hash1 = thatCurGroup.hash1;
            thisNewHashGroup.entry1 = thatCurGroup.entry1;
          }
          if (!handled2) {
            thisNewHashGroup.hash2 = thatCurGroup.hash2;
            thisNewHashGroup.entry2 = thatCurGroup.entry2;
          }
          if (!handled3) {
            thisNewHashGroup.hash3 = thatCurGroup.hash3;
            thisNewHashGroup.entry3 = thatCurGroup.entry3;
          }
          thisNewHashGroup.prev = thisNewestHeadGroup;

          thisNewestHeadGroup = thisNewHashGroup;
        }
      }

      return thisNewestHeadGroup;
    }

    boolean replaceOrInsertInChain(int hash, Entry entry) {
      return (this.replaceInChain(hash, entry) != null) || this.insertInChain(hash, entry);
    }

    Entry replaceInChain(int hash, Entry entry) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        Entry prevEntry = curGroup._replace(hash, entry);
        if (prevEntry != null) return prevEntry;
      }
      return null;
    }

    Entry _replace(int hash, Entry entry) {
      // if ( this._mayContain(hash) ) return null;

      // first check to see if the item is already present
      Entry prevEntry = null;
      if (this.hash0 == hash && this.entry0.matches(entry.tag)) {
        prevEntry = this.entry0;
        this.entry0 = entry;
      } else if (this.hash1 == hash && this.entry1.matches(entry.tag)) {
        prevEntry = this.entry1;
        this.entry1 = entry;
      } else if (this.hash2 == hash && this.entry2.matches(entry.tag)) {
        prevEntry = this.entry2;
        this.entry2 = entry;
      } else if (this.hash3 == hash && this.entry3.matches(entry.tag)) {
        prevEntry = this.entry3;
        this.entry3 = entry;
      }

      return prevEntry;
    }

    boolean insertInChain(int hash, Entry entry) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        if (curGroup._insert(hash, entry)) return true;
      }
      return false;
    }

    boolean _insert(int hash, Entry entry) {
      boolean inserted = false;
      if (this.hash0 == 0) {
        this.hash0 = hash;
        this.entry0 = entry;

        inserted = true;
      } else if (this.hash1 == 0) {
        this.hash1 = hash;
        this.entry1 = entry;

        inserted = true;
      } else if (this.hash2 == 0) {
        this.hash2 = hash;
        this.entry2 = entry;

        inserted = true;
      } else if (this.hash3 == 0) {
        this.hash3 = hash;
        this.entry3 = entry;

        inserted = true;
      }
      return inserted;
    }

    BucketGroup removeGroupInChain(BucketGroup removeGroup) {
      BucketGroup firstGroup = this;
      if (firstGroup == removeGroup) {
        return firstGroup.prev;
      }

      for (BucketGroup priorGroup = firstGroup, curGroup = priorGroup.prev;
          curGroup != null;
          priorGroup = curGroup, curGroup = priorGroup.prev) {
        if (curGroup == removeGroup) {
          priorGroup.prev = curGroup.prev;
        }
      }
      return firstGroup;
    }

    Entry _remove(int hash, String tag) {
      Entry existingEntry = null;
      if (this.hash0 == hash && this.entry0.matches(tag)) {
        existingEntry = this.entry0;

        this.hash0 = 0;
        this.entry0 = null;
      } else if (this.hash1 == hash && this.entry1.matches(tag)) {
        existingEntry = this.entry1;

        this.hash1 = 0;
        this.entry1 = null;
      } else if (this.hash2 == hash && this.entry2.matches(tag)) {
        existingEntry = this.entry2;

        this.hash2 = 0;
        this.entry2 = null;
      } else if (this.hash3 == hash && this.entry3.matches(tag)) {
        existingEntry = this.entry3;

        this.hash3 = 0;
        this.entry3 = null;
      }
      return existingEntry;
    }

    void forEachInChain(Consumer<? super Entry> consumer) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._forEach(consumer);
      }
    }

    void _forEach(Consumer<? super Entry> consumer) {
      if (this.entry0 != null) consumer.accept(this.entry0);
      if (this.entry1 != null) consumer.accept(this.entry1);
      if (this.entry2 != null) consumer.accept(this.entry2);
      if (this.entry3 != null) consumer.accept(this.entry3);
    }

    <T> void forEachInChain(T thisObj, BiConsumer<T, ? super Entry> consumer) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._forEach(thisObj, consumer);
      }
    }

    <T> void _forEach(T thisObj, BiConsumer<T, ? super Entry> consumer) {
      if (this.entry0 != null) consumer.accept(thisObj, this.entry0);
      if (this.entry1 != null) consumer.accept(thisObj, this.entry1);
      if (this.entry2 != null) consumer.accept(thisObj, this.entry2);
      if (this.entry3 != null) consumer.accept(thisObj, this.entry3);
    }

    <T, U> void forEachInChain(T thisObj, U otherObj, TriConsumer<T, U, ? super Entry> consumer) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._forEach(thisObj, otherObj, consumer);
      }
    }

    <T, U> void _forEach(T thisObj, U otherObj, TriConsumer<T, U, ? super Entry> consumer) {
      if (this.entry0 != null) consumer.accept(thisObj, otherObj, this.entry0);
      if (this.entry1 != null) consumer.accept(thisObj, otherObj, this.entry1);
      if (this.entry2 != null) consumer.accept(thisObj, otherObj, this.entry2);
      if (this.entry3 != null) consumer.accept(thisObj, otherObj, this.entry3);
    }

    void fillMapFromChain(Map<? super String, ? super Object> map) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._fillMap(map);
      }
    }

    void _fillMap(Map<? super String, ? super Object> map) {
      Entry entry0 = this.entry0;
      if (entry0 != null) map.put(entry0.tag, entry0.objectValue());

      Entry entry1 = this.entry1;
      if (entry1 != null) map.put(entry1.tag, entry1.objectValue());

      Entry entry2 = this.entry2;
      if (entry2 != null) map.put(entry2.tag, entry2.objectValue());

      Entry entry3 = this.entry3;
      if (entry3 != null) map.put(entry3.tag, entry3.objectValue());
    }

    void fillStringMapFromChain(Map<? super String, ? super String> map) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._fillStringMap(map);
      }
    }

    void _fillStringMap(Map<? super String, ? super String> map) {
      Entry entry0 = this.entry0;
      if (entry0 != null) map.put(entry0.tag, entry0.stringValue());

      Entry entry1 = this.entry1;
      if (entry1 != null) map.put(entry1.tag, entry1.stringValue());

      Entry entry2 = this.entry2;
      if (entry2 != null) map.put(entry2.tag, entry2.stringValue());

      Entry entry3 = this.entry3;
      if (entry3 != null) map.put(entry3.tag, entry3.stringValue());
    }

    BucketGroup cloneChain() {
      BucketGroup thisClone = this._cloneEntries();

      BucketGroup thisPriorClone = thisClone;
      for (BucketGroup curGroup = this.prev; curGroup != null; curGroup = curGroup.prev) {
        BucketGroup newClone = curGroup._cloneEntries();
        thisPriorClone.prev = newClone;

        thisPriorClone = newClone;
      }

      return thisClone;
    }

    BucketGroup _cloneEntries() {
      return new BucketGroup(
          this.hash0, this.entry0,
          this.hash1, this.entry1,
          this.hash2, this.entry2,
          this.hash3, this.entry3);
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder(32);
      builder.append('[');
      for (int i = 0; i < BucketGroup.LEN; ++i) {
        if (builder.length() != 0) builder.append(", ");

        builder.append(this._entryAt(i));
      }
      builder.append(']');
      return builder.toString();
    }
  }

  private static final class Entries extends AbstractSet<Map.Entry<String, Object>> {
    private final TagMap map;

    Entries(TagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.computeSize();
    }

    @Override
    public boolean isEmpty() {
      return this.map.checkIfEmpty();
    }

    @Override
    public Iterator<Map.Entry<String, Object>> iterator() {
      @SuppressWarnings({"rawtypes", "unchecked"})
      Iterator<Map.Entry<String, Object>> iter = (Iterator) this.map.iterator();
      return iter;
    }
  }

  private static final class Keys extends AbstractSet<String> {
    private final TagMap map;

    Keys(TagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.computeSize();
    }

    @Override
    public boolean isEmpty() {
      return this.map.checkIfEmpty();
    }

    @Override
    public boolean contains(Object o) {
      return this.map.containsKey(o);
    }

    @Override
    public Iterator<String> iterator() {
      return new KeysIterator(this.map);
    }
  }

  static final class KeysIterator extends MapIterator<String> {
    KeysIterator(TagMap map) {
      super(map);
    }

    @Override
    public String next() {
      return this.nextEntry().tag();
    }
  }

  private static final class Values extends AbstractCollection<Object> {
    private final TagMap map;

    Values(TagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.computeSize();
    }

    @Override
    public boolean isEmpty() {
      return this.map.checkIfEmpty();
    }

    @Override
    public boolean contains(Object o) {
      return this.map.containsValue(o);
    }

    @Override
    public Iterator<Object> iterator() {
      return new ValuesIterator(this.map);
    }
  }

  static final class ValuesIterator extends MapIterator<Object> {
    ValuesIterator(TagMap map) {
      super(map);
    }

    @Override
    public Object next() {
      return this.nextEntry().objectValue();
    }
  }
}

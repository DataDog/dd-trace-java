package datadog.trace.api;

import datadog.trace.api.TagMap.BucketGroup;
import datadog.trace.api.TagMap.Entry;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The hash {@link IdStore}: known tags in their own bucket array ({@link HashSupport}), keyed by
 * id, reading through to the frozen parent's id store.
 *
 * <p>A read checks the slot masks before probing: a clear local bit skips the buckets, and a clear
 * inherited bit skips the read-through. Removing an inherited tag puts that tag's shared tombstone
 * ({@link Entry#tombstoneOf}) in the local buckets, so it allocates nothing, and sets its slot in
 * the tombstone mask; the bucket entry is the exact state, the mask the hint. A tombstone is never
 * counted, iterated or returned.
 */
final class HashIdStore extends IdStore {
  final Object[] buckets;

  /** Live local entries, not counting tombstones. */
  int count;

  /** The frozen parent's id store, or null. */
  final HashIdStore parent;

  HashIdStore(IdStore parent) {
    super(parent);
    this.buckets = HashSupport.newBuckets();
    this.parent = (HashIdStore) parent;
  }

  /** An empty parentless store over one bucket, for {@link TagMap#EMPTY}, which is frozen. */
  static HashIdStore newEmpty() {
    return new HashIdStore(new Object[1]);
  }

  private HashIdStore(Object[] buckets) {
    super(null);
    this.buckets = buckets;
    this.parent = null;
  }

  @Override
  public Entry get(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw notKnown(tagId);
    }
    return this.getVisible(tagId, slotBit(tagId));
  }

  /** {@link #get} for a validated id and its slot bit. */
  private Entry getVisible(long tagId, long bit) {
    if (this.mayHaveLocal(bit)) {
      Entry local = HashSupport.findById(this.buckets, Entry.bucketHash(tagId), tagId);
      if (local != null) {
        return local.tombstone ? null : local;
      }
    }
    return this.mayInherit(bit) ? this.parent.getVisible(tagId, bit) : null;
  }

  @Override
  public Object getKnownValue(long tagId) {
    Entry entry = this.get(tagId);
    return entry == null ? null : entry.objectValue();
  }

  @Override
  public void set(long tagId, Object value) {
    this.put(new Entry(tagId, null, Entry.ANY, 0L, value));
  }

  @Override
  public void set(long tagId, CharSequence value) {
    this.put(new Entry(tagId, null, EntryReader.OBJECT, 0L, value));
  }

  @Override
  public void set(long tagId, boolean value) {
    this.put(
        new Entry(
            tagId, null, EntryReader.BOOLEAN, Entry.boolean2Prim(value), Boolean.valueOf(value)));
  }

  @Override
  public void set(long tagId, int value) {
    this.put(new Entry(tagId, null, EntryReader.INT, Entry.int2Prim(value), null));
  }

  @Override
  public void set(long tagId, long value) {
    this.put(new Entry(tagId, null, EntryReader.LONG, Entry.long2Prim(value), null));
  }

  @Override
  public void set(long tagId, float value) {
    this.put(new Entry(tagId, null, EntryReader.FLOAT, Entry.float2Prim(value), null));
  }

  @Override
  public void set(long tagId, double value) {
    this.put(new Entry(tagId, null, EntryReader.DOUBLE, Entry.double2Prim(value), null));
  }

  @Override
  public Entry put(Entry knownEntry) {
    Entry replaced = HashSupport.put(this.buckets, knownEntry);
    this.occupancy |= slotBit(knownEntry.tagHash);
    if (replaced == null || replaced.tombstone) {
      this.count++; // a tombstone replaced is a removal undone: no prior local entry
      return null;
    }
    return replaced;
  }

  @Override
  public Entry getAndPut(Entry knownEntry) {
    long tagId = knownEntry.tagHash;
    long bit = slotBit(tagId);
    Entry replaced = HashSupport.put(this.buckets, knownEntry);
    this.occupancy |= bit;
    if (replaced != null && !replaced.tombstone) {
      return replaced; // replaced a local entry -> that is the prior value
    }
    this.count++;
    if (replaced != null || !this.mayInherit(bit)) {
      return null; // it was tombstoned, so not visible, or nothing to inherit
    }
    return this.parent.getVisible(tagId, bit);
  }

  @Override
  public Entry remove(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw notKnown(tagId);
    }
    long bit = slotBit(tagId);
    int hash = Entry.bucketHash(tagId);

    Entry local = this.mayHaveLocal(bit) ? HashSupport.findById(this.buckets, hash, tagId) : null;
    if (local != null) {
      if (local.tombstone) {
        return null; // already removed
      }
      HashSupport.remove(this.buckets, hash, local.tag);
      this.count--;
    }
    if (this.mayInherit(bit)) {
      // Read-through: hide an inherited value with a tombstone. The prior *visible* value is the
      // local entry if there was one, otherwise the inherited one.
      Entry inherited = this.parent.getVisible(tagId, bit);
      if (inherited != null) {
        HashSupport.put(this.buckets, Entry.tombstoneOf(tagId));
        this.tombstoneMask |= bit;
        return local != null ? local : inherited;
      }
    }
    return local;
  }

  @Override
  int size() {
    return this.parent == null ? this.count : this.count + this.visibleParentCount();
  }

  /** Ancestor entries not shadowed or tombstoned by a nearer level, nearest-level-wins. */
  private int visibleParentCount() {
    int[] count = new int[1];
    this.forEachParent(count, null, (c, unused, entry) -> c[0]++);
    return count[0];
  }

  @Override
  boolean isEmpty() {
    if (this.count != 0) {
      return false;
    }
    HashIdStore parent = this.parent;
    if (parent == null) {
      return true;
    }
    if (this.tombstoneMask == 0L) {
      return parent.isEmpty(); // nothing hidden: the parent's tags are all visible
    }
    return this.visibleParentCount() == 0;
  }

  @Override
  boolean isDefinitelyEmpty() {
    for (HashIdStore level = this; level != null; level = level.parent) {
      if (level.count != 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  int estimateSize() {
    int total = 0;
    for (HashIdStore level = this; level != null; level = level.parent) {
      total += level.count;
    }
    return total;
  }

  @Override
  boolean hasParent() {
    return this.parent != null;
  }

  /**
   * Whether {@code parentEntry}, from the ancestor level {@code fromAncestor}, is visible through
   * this leaf: no nearer level holds an entry or a tombstone for it. A nearer level whose masks
   * rule the slot out is skipped without a probe.
   */
  boolean parentEntryVisible(Entry parentEntry, HashIdStore fromAncestor) {
    long tagId = parentEntry.tagHash;
    long bit = slotBit(tagId);
    int hash = parentEntry.hash();
    for (HashIdStore nearer = this; nearer != fromAncestor; nearer = nearer.parent) {
      if (nearer.mayHaveLocal(bit) && HashSupport.findById(nearer.buckets, hash, tagId) != null) {
        return false;
      }
    }
    return true;
  }

  @Override
  void forEach(Consumer<? super EntryReader> consumer) {
    HashSupport.forEach(this.buckets, consumer);
    if (this.parent != null) {
      this.forEachParent(consumer, null, (c, unused, entry) -> c.accept(entry));
    }
  }

  @Override
  <T> void forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
    HashSupport.forEach(this.buckets, thisObj, consumer);
    if (this.parent != null) {
      this.forEachParent(thisObj, consumer, (t, c, entry) -> c.accept(t, entry));
    }
  }

  @Override
  <T, U> void forEach(T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    HashSupport.forEach(this.buckets, thisObj, otherObj, consumer);
    if (this.parent != null) {
      this.forEachParent(thisObj, otherObj, consumer);
    }
  }

  private <T, U> void forEachParent(
      T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    for (HashIdStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object bucket = parentBuckets[i];
        if (bucket instanceof Entry) {
          Entry entry = (Entry) bucket;
          if (!entry.tombstone && this.parentEntryVisible(entry, ancestor)) {
            consumer.accept(thisObj, otherObj, entry);
          }
        } else if (bucket instanceof BucketGroup) {
          for (BucketGroup group = (BucketGroup) bucket; group != null; group = group.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry entry = group._entryAt(j);
              if (entry != null && !entry.tombstone && this.parentEntryVisible(entry, ancestor)) {
                consumer.accept(thisObj, otherObj, entry);
              }
            }
          }
        }
      }
    }
  }

  @Override
  Cursor cursor() {
    return new HashCursor(this);
  }

  /** Walks the leaf's buckets, then each ancestor's, skipping tombstones and shadowed entries. */
  private static final class HashCursor extends Cursor {
    private final HashIdStore leaf;
    private HashIdStore level;
    private final HashSupport.BucketWalker walker = new HashSupport.BucketWalker();

    HashCursor(HashIdStore leaf) {
      this.leaf = leaf;
      this.level = leaf;
      this.walker.reset(leaf.buckets);
    }

    @Override
    EntryReader next() {
      HashIdStore level = this.level;
      while (level != null) {
        Entry entry = this.walker.next();
        if (entry != null) {
          if (!entry.tombstone
              && (level == this.leaf || this.leaf.parentEntryVisible(entry, level))) {
            return entry;
          }
          continue;
        }
        level = level.parent;
        this.level = level;
        if (level != null) {
          this.walker.reset(level.buckets);
        }
      }
      return null;
    }
  }

  @Override
  IdStore copy() {
    HashIdStore copy = new HashIdStore(this.parent);
    HashSupport.copyInto(copy.buckets, this.buckets);
    copy.count = this.count;
    copy.occupancy = this.occupancy;
    copy.tombstoneMask = this.tombstoneMask;
    return copy;
  }

  @Override
  void putAllLocal(IdStore other) {
    HashIdStore that = (HashIdStore) other;
    if (that.count == 0) {
      return;
    }
    if (this.tombstoneMask != 0L) {
      // A bucket merge would count a replaced tombstone as a replacement, so put one at a time.
      HashSupport.forEach(that.buckets, this, (self, entry) -> self.put(entry.entry()));
      return;
    }
    if (this.count == 0) {
      HashSupport.copyInto(this.buckets, that.buckets);
      this.count = that.count;
    } else {
      this.count += HashSupport.merge(this.buckets, that.buckets);
    }
    this.occupancy |= that.occupancy;
  }

  @Override
  void fillMapLocal(Map<? super String, Object> map) {
    HashSupport.fillMap(this.buckets, map);
  }

  @Override
  void fillStringMapLocal(Map<? super String, ? super String> map) {
    HashSupport.fillStringMap(this.buckets, map);
  }

  @Override
  void checkIntegrity() {
    HashSupport.checkBuckets(this.buckets);
    if (this.count != this.computeSize()) {
      throw new IllegalStateException("incorrect size");
    }
  }

  @Override
  int computeSize() {
    return HashSupport.count(this.buckets);
  }

  @Override
  String toInternalString() {
    return HashSupport.toInternalString(this.buckets);
  }
}

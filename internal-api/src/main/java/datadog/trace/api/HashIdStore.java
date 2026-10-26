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
 * <p>The buckets are the exact state: a read probes them, and a local miss reads through to the
 * parent if there is one. Removing an inherited tag puts that tag's shared tombstone ({@link
 * Entry#tombstoneOf}) in the local buckets, so it allocates nothing. A tombstone is never counted,
 * iterated or returned.
 */
final class HashIdStore extends IdStore {
  /** An empty parentless store over one bucket, for {@link TagMap#EMPTY}, which is frozen. */
  static HashIdStore createEmpty() {
    return new HashIdStore(new Object[1]);
  }

  /** Walks the leaf's buckets, then each ancestor's, skipping tombstones and shadowed entries. */
  private static final class HashCursor implements Cursor {
    private final HashIdStore leaf;
    private HashIdStore level;
    private final HashSupport.BucketWalker walker = new HashSupport.BucketWalker();

    HashCursor(HashIdStore leaf) {
      this.leaf = leaf;
      this.level = leaf;
      this.walker.reset(leaf.buckets);
    }

    @Override
    public EntryReader next() {
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

  final Object[] buckets;

  /** Live local entries, not counting tombstones. */
  int count;

  /** The frozen parent's id store, or null. */
  final HashIdStore parent;

  /** Whether the buckets may hold a tombstone; a hint that keeps the common case on fast paths. */
  boolean hasTombstones;

  HashIdStore(IdStore parent) {
    this.buckets = HashSupport.newBuckets();
    this.parent = (HashIdStore) parent;
  }

  private HashIdStore(Object[] buckets) {
    this.buckets = buckets;
    this.parent = null;
  }

  @Override
  public Entry getEntry(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw notKnown(tagId);
    }
    return this.getVisible(tagId);
  }

  /** The stored Entry is itself a stable reader, so this is {@link #getEntry}. */
  @Override
  public Entry getEntryReader(long tagId) {
    return this.getEntry(tagId);
  }

  /** {@link #getEntry} for a validated id. */
  private Entry getVisible(long tagId) {
    Entry local = HashSupport.findById(this.buckets, Entry.bucketHash(tagId), tagId);
    if (local != null) {
      return local.tombstone ? null : local;
    }
    HashIdStore parent = this.parent;
    return parent == null ? null : parent.getVisible(tagId);
  }

  @Override
  public Object load(long tagId) {
    Entry entry = this.getEntry(tagId);
    return entry == null ? null : entry.objectValue();
  }

  @Override
  public String loadString(long tagId) {
    Entry entry = this.getEntry(tagId);
    return entry == null ? null : entry.stringValue();
  }

  @Override
  public void store(long tagId, Object value) {
    if (value == null) {
      this.remove(tagId); // a null value removes the tag
      return;
    }
    this.put(Entry.newAnyEntry(tagId, value));
  }

  @Override
  public void store(long tagId, CharSequence value) {
    if (value == null) {
      this.remove(tagId); // a null value removes the tag
      return;
    }
    this.put(Entry.newObjectEntry(tagId, value));
  }

  @Override
  public void store(long tagId, boolean value) {
    this.put(Entry.newBooleanEntry(tagId, value));
  }

  @Override
  public void store(long tagId, int value) {
    this.put(Entry.newIntEntry(tagId, value));
  }

  @Override
  public void store(long tagId, long value) {
    this.put(Entry.newLongEntry(tagId, value));
  }

  @Override
  public void store(long tagId, float value) {
    this.put(Entry.newFloatEntry(tagId, value));
  }

  @Override
  public void store(long tagId, double value) {
    this.put(Entry.newDoubleEntry(tagId, value));
  }

  @Override
  public Entry put(EntryReader entryReader) {
    Entry entry = entryReader.entry();
    if (entry == null) {
      return null; // a reader with no entry is a no-op, as for TagMap.set
    }
    Entry replaced = HashSupport.put(this.buckets, entry);
    if (replaced == null || replaced.tombstone) {
      this.count++; // a tombstone replaced is a removal undone: no prior local entry
      return null;
    }
    return replaced;
  }

  @Override
  public Entry getAndPut(EntryReader entryReader) {
    Entry entry = entryReader.entry();
    if (entry == null) {
      return null; // a reader with no entry is a no-op
    }
    Entry replaced = HashSupport.put(this.buckets, entry);
    if (replaced != null && !replaced.tombstone) {
      return replaced; // replaced a local entry -> that is the prior value
    }
    this.count++;
    HashIdStore parent = this.parent;
    if (replaced != null || parent == null) {
      return null; // it was tombstoned, so not visible, or nothing to inherit
    }
    return parent.getVisible(entry.tagHash);
  }

  @Override
  public Entry remove(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw notKnown(tagId);
    }
    int hash = Entry.bucketHash(tagId);

    Entry local = HashSupport.findById(this.buckets, hash, tagId);
    if (local != null) {
      if (local.tombstone) {
        return null; // already removed
      }
      HashSupport.remove(this.buckets, hash, local.tag);
      this.count--;
    }
    HashIdStore parent = this.parent;
    if (parent != null) {
      // Read-through: hide an inherited value with a tombstone. The prior *visible* value is the
      // local entry if there was one, otherwise the inherited one.
      Entry inherited = parent.getVisible(tagId);
      if (inherited != null) {
        HashSupport.put(this.buckets, Entry.tombstoneOf(tagId));
        this.hasTombstones = true;
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
    // a plain loop, not forEachParent: counting through a lambda would allocate a counter
    int count = 0;
    for (HashIdStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object bucket = parentBuckets[i];
        if (bucket instanceof Entry) {
          Entry entry = (Entry) bucket;
          if (!entry.tombstone && this.parentEntryVisible(entry, ancestor)) {
            count++;
          }
        } else if (bucket instanceof BucketGroup) {
          for (BucketGroup group = (BucketGroup) bucket; group != null; group = group.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry entry = group._entryAt(j);
              if (entry != null && !entry.tombstone && this.parentEntryVisible(entry, ancestor)) {
                count++;
              }
            }
          }
        }
      }
    }
    return count;
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
    if (!this.hasTombstones) {
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
   * this leaf: no nearer level holds an entry or a tombstone for it.
   */
  boolean parentEntryVisible(Entry parentEntry, HashIdStore fromAncestor) {
    long tagId = parentEntry.tagHash;
    int hash = parentEntry.hash();
    for (HashIdStore nearer = this; nearer != fromAncestor; nearer = nearer.parent) {
      if (HashSupport.findById(nearer.buckets, hash, tagId) != null) {
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

  @Override
  IdStore copy() {
    HashIdStore copy = new HashIdStore(this.parent);
    HashSupport.copyInto(copy.buckets, this.buckets);
    copy.count = this.count;
    copy.hasTombstones = this.hasTombstones;
    return copy;
  }

  @Override
  void putAllLocal(IdStore other) {
    HashIdStore that = (HashIdStore) other;
    if (that.count == 0) {
      return;
    }
    if (this.hasTombstones) {
      // A bucket merge would count a replaced tombstone as a replacement, so put one at a time.
      HashSupport.forEach(that.buckets, this, (self, entryReader) -> self.put(entryReader));
      return;
    }
    if (this.count == 0) {
      HashSupport.copyInto(this.buckets, that.buckets);
      this.count = that.count;
    } else {
      this.count += HashSupport.merge(this.buckets, that.buckets);
    }
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

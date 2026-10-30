package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.BucketGroup;
import datadog.trace.api.TagMap.Entry;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The custom-tag half of a {@link TagMap}: custom tags by name, in their own bucket array ({@link
 * HashSupport}), reading through to the frozen parent's custom store. Name-keyed methods take only
 * custom tag names (asserted): a known tag is resolved to its id first, and goes to the {@link
 * IdStore}.
 *
 * <p>Values move by {@code store}, as through {@link TagStore}; entries by {@code getEntry}, {@code
 * put} and {@code remove}. Unlike {@link TagStore}, writes take no nulls: the owner turns a null
 * value into {@code remove} and skips a null entry before calling.
 *
 * <p>Created lazily by its owner -- a {@link TagMap}, or a span in the tracer core -- on the first
 * custom write ({@link #create}); until then the owner borrows its parent's custom store, if any,
 * read-only. No locking: guarded by the owner's lock.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public final class HashStore {
  final Object[] buckets;

  /** Local entries. */
  int count;

  /** The frozen parent's custom store, or null. */
  final HashStore parent;

  /**
   * Parent keys removed locally (read-through tombstones): a tombstone stops read-through for its
   * key. Allocated on the first such removal, so while it is {@code null} the hot paths skip the
   * tombstone checks.
   */
  private Set<String> removedFromParent;

  /**
   * A fresh custom store for an owner's first custom write, reading through to {@code borrowed},
   * the store it borrowed until now (may be null).
   */
  public static HashStore create(HashStore borrowed) {
    return new HashStore(borrowed);
  }

  HashStore(HashStore parent) {
    this.buckets = HashSupport.newBuckets();
    this.parent = parent;
  }

  /** Exact count, including the visible part of the read-through chain. */
  int size() {
    return this.parent == null ? this.count : this.count + this.visibleParentCount();
  }

  /** Ancestor entries not shadowed or tombstoned by a nearer level, nearest-level-wins. */
  private int visibleParentCount() {
    // a plain loop, not forEachParent: counting through a lambda would allocate a counter
    int count = 0;
    for (HashStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object bucket = parentBuckets[i];
        if (bucket instanceof Entry) {
          Entry entry = (Entry) bucket;
          if (this.parentEntryVisible(entry, ancestor)) {
            count++;
          }
        } else if (bucket instanceof BucketGroup) {
          for (BucketGroup group = (BucketGroup) bucket; group != null; group = group.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry entry = group._entryAt(j);
              if (entry != null && this.parentEntryVisible(entry, ancestor)) {
                count++;
              }
            }
          }
        }
      }
    }
    return count;
  }

  boolean isEmpty() {
    if (this.count != 0) {
      return false;
    }
    HashStore parent = this.parent;
    if (parent == null) {
      return true;
    }
    if (this.removedFromParent == null) {
      return parent.isEmpty(); // nothing hidden: the parent's tags are all visible
    }
    return this.visibleParentCount() == 0;
  }

  /** Cheap: no level of the chain holds a local entry (ignores shadowing and tombstones). */
  boolean isDefinitelyEmpty() {
    for (HashStore level = this; level != null; level = level.parent) {
      if (level.count != 0) {
        return false;
      }
    }
    return true;
  }

  /** Upper bound: the local counts of every level. */
  int estimateSize() {
    int total = 0;
    for (HashStore level = this; level != null; level = level.parent) {
      total += level.count;
    }
    return total;
  }

  /** The custom tag {@code tag}'s entry, reading through to the parent chain, or null. */
  @Nullable
  public Entry getEntry(String tag) {
    assert KnownTagCodec.keyOf(tag) == 0 : "'" + tag + "' is a known tag; look it up by its id";
    return this.get(tag, Entry.bucketHash(Entry.customHash(tag)));
  }

  private Entry get(String tag, int hash) {
    Entry local = HashSupport.find(this.buckets, hash, tag);
    if (local != null) {
      return local;
    }
    HashStore parent = this.parent;
    if (parent == null) {
      return null;
    }
    if (this.removedFromParent != null && this.removedFromParent.contains(tag)) {
      return null; // tombstoned: removed locally, do not read through
    }
    return parent.get(tag, hash);
  }

  /*
   * Stores the custom tag {@code tag}, which must not be a known tag's name.
   */

  public void store(String tag, @Nonnull Object value) {
    this.put(Entry.newAnyEntry(tag, value));
  }

  public void store(String tag, @Nonnull CharSequence value) {
    this.put(Entry.newObjectEntry(tag, value));
  }

  public void store(String tag, boolean value) {
    this.put(Entry.newBooleanEntry(tag, value));
  }

  public void store(String tag, int value) {
    this.put(Entry.newIntEntry(tag, value));
  }

  public void store(String tag, long value) {
    this.put(Entry.newLongEntry(tag, value));
  }

  public void store(String tag, float value) {
    this.put(Entry.newFloatEntry(tag, value));
  }

  public void store(String tag, double value) {
    this.put(Entry.newDoubleEntry(tag, value));
  }

  /**
   * Inserts or replaces a custom tag's entry, returning the local entry it replaced (or null). Does
   * not consult the read-through parent.
   */
  @Nullable
  public Entry put(@Nonnull Entry newEntry) {
    // Re-setting a key clears any read-through tombstone for it (the new value overrides the
    // removal). Gated on the lazy field, so this is a no-op for the common no-tombstone case.
    if (this.removedFromParent != null) {
      this.removedFromParent.remove(newEntry.tag);
    }
    Entry replaced = HashSupport.put(this.buckets, newEntry);
    if (replaced == null) {
      this.count++;
    }
    return replaced;
  }

  /** {@link #put}, returning the prior visible entry -- local, else read through. */
  @Nullable
  public Entry getAndPut(@Nonnull Entry newEntry) {
    // Capture whether the key was tombstoned BEFORE put clears it: a tombstoned key had no visible
    // prior value (it was removed), so getAndPut must report null rather than the parent's.
    boolean wasTombstoned =
        this.removedFromParent != null && this.removedFromParent.contains(newEntry.tag);

    Entry priorLocal = this.put(newEntry);
    if (priorLocal != null) {
      return priorLocal;
    }
    if (wasTombstoned || this.parent == null) {
      return null;
    }
    return this.parent.get(newEntry.tag, newEntry.hash());
  }

  /**
   * Removes the custom tag {@code tag}, returning the prior visible entry. Removing a key the
   * parent chain still exposes also installs a tombstone, so it stops reading through.
   */
  @Nullable
  public Entry remove(String tag) {
    assert KnownTagCodec.keyOf(tag) == 0 : "'" + tag + "' is a known tag; remove it by its id";
    int hash = Entry.bucketHash(Entry.customHash(tag));
    Entry localRemoved = HashSupport.remove(this.buckets, hash, tag);
    if (localRemoved != null) {
      this.count--;
    }

    HashStore parent = this.parent;
    if (parent != null) {
      boolean alreadyTombstoned =
          this.removedFromParent != null && this.removedFromParent.contains(tag);
      if (!alreadyTombstoned) {
        Entry parentEntry = parent.get(tag, hash);
        if (parentEntry != null) {
          if (this.removedFromParent == null) {
            // Small initial capacity: this set is rare and almost always holds only a handful of
            // tombstoned keys, so the default 16-bucket HashSet table would be wasteful.
            this.removedFromParent = new HashSet<>(4);
          }
          this.removedFromParent.add(tag);
          return localRemoved != null ? localRemoved : parentEntry;
        }
      }
    }
    return localRemoved;
  }

  /**
   * Whether {@code parentEntry}, from the ancestor level {@code fromAncestor}, is visible through
   * this leaf: not shadowed and not tombstoned by any nearer level.
   */
  boolean parentEntryVisible(Entry parentEntry, HashStore fromAncestor) {
    int hash = parentEntry.hash();
    String tag = parentEntry.tag;
    for (HashStore nearer = this; nearer != fromAncestor; nearer = nearer.parent) {
      if (nearer.removedFromParent != null && nearer.removedFromParent.contains(tag)) {
        return false;
      }
      if (HashSupport.find(nearer.buckets, hash, tag) != null) {
        return false;
      }
    }
    return true;
  }

  /** Visits the local entries, then the visible inherited ones. */
  void forEach(Consumer<? super EntryReader> consumer) {
    HashSupport.forEach(this.buckets, consumer);
    if (this.parent != null) {
      this.forEachParent(consumer, null, (c, unused, entry) -> c.accept(entry));
    }
  }

  <T> void forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
    HashSupport.forEach(this.buckets, thisObj, consumer);
    if (this.parent != null) {
      this.forEachParent(thisObj, consumer, (t, c, entry) -> c.accept(t, entry));
    }
  }

  <T, U> void forEach(T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    HashSupport.forEach(this.buckets, thisObj, otherObj, consumer);
    if (this.parent != null) {
      this.forEachParent(thisObj, otherObj, consumer);
    }
  }

  private <T, U> void forEachParent(
      T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    for (HashStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object bucket = parentBuckets[i];
        if (bucket instanceof Entry) {
          Entry entry = (Entry) bucket;
          if (this.parentEntryVisible(entry, ancestor)) {
            consumer.accept(thisObj, otherObj, entry);
          }
        } else if (bucket instanceof BucketGroup) {
          for (BucketGroup group = (BucketGroup) bucket; group != null; group = group.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry entry = group._entryAt(j);
              if (entry != null && this.parentEntryVisible(entry, ancestor)) {
                consumer.accept(thisObj, otherObj, entry);
              }
            }
          }
        }
      }
    }
  }

  /**
   * An independently mutable copy: the same (frozen, shared) parent, this store's own entries
   * (shared Entry objects) and its tombstones.
   */
  HashStore copy() {
    HashStore copy = new HashStore(this.parent);
    HashSupport.copyInto(copy.buckets, this.buckets);
    copy.count = this.count;
    if (this.removedFromParent != null) {
      copy.removedFromParent = new HashSet<>(this.removedFromParent);
    }
    return copy;
  }

  /** Copies the entries of the parentless store {@code that}, sharing Entry objects. */
  void putAllLocal(HashStore that) {
    if (that.count == 0) {
      return;
    }
    if (this.count == 0) {
      HashSupport.copyInto(this.buckets, that.buckets);
      this.count = that.count;
    } else {
      this.count += HashSupport.merge(this.buckets, that.buckets);
    }
  }

  void fillMapLocal(Map<? super String, Object> map) {
    HashSupport.fillMap(this.buckets, map);
  }

  void fillStringMapLocal(Map<? super String, ? super String> map) {
    HashSupport.fillStringMap(this.buckets, map);
  }

  /** Tests only: throws if the store's structure is inconsistent. */
  void checkIntegrity() {
    HashSupport.checkBuckets(this.buckets);
    if (this.count != this.computeSize()) {
      throw new IllegalStateException("incorrect size");
    }
  }

  /** Tests only: the local count, recomputed from the structure. */
  int computeSize() {
    return HashSupport.count(this.buckets);
  }

  /** For deep debugging. */
  String toInternalString() {
    return HashSupport.toInternalString(this.buckets);
  }
}

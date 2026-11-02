package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.BucketGroup;
import datadog.trace.api.TagMap.Entry;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.internal.VisibleForTesting;
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
 * put} and {@code remove}. {@link TagStore} treats a null value as a removal; this store takes no
 * nulls at all, so its owner turns a null value into {@code remove} and skips a null entry before
 * calling.
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
   * Finds the custom store an owner borrows read-only until its first custom write.
   *
   * @param parent the owner's frozen parent map; may be null
   * @return {@code parent}'s custom store, or null if there is no parent or it has none
   */
  public static HashStore inheritedFrom(TagMap parent) {
    return parent == null ? null : parent.nameStore;
  }

  /**
   * Creates the custom store for an owner's first custom write.
   *
   * @param borrowed the store the owner borrowed until now, which the new store reads through to;
   *     may be null
   * @return a fresh, empty custom store
   */
  public static HashStore create(HashStore borrowed) {
    return new HashStore(borrowed);
  }

  HashStore(HashStore parent) {
    this.buckets = HashSupport.newBuckets();
    this.parent = parent;
  }

  /**
   * Counts the visible tags.
   *
   * @return the exact count, including the visible part of the read-through chain
   */
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

  /**
   * Cheaply checks for emptiness, ignoring shadowing and tombstones.
   *
   * @return true if no level of the chain holds a local entry, so the store is empty; false means
   *     only "maybe not", since tombstones can hide every inherited tag
   */
  boolean isDefinitelyEmpty() {
    for (HashStore level = this; level != null; level = level.parent) {
      if (level.count != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Bounds the visible count from above, without resolving shadowing or tombstones.
   *
   * @return the sum of the local counts of every level
   */
  int estimateSize() {
    int total = 0;
    for (HashStore level = this; level != null; level = level.parent) {
      total += level.count;
    }
    return total;
  }

  /**
   * Looks up a custom tag, reading through to the parent chain.
   *
   * @param tag the custom tag's name; must not be a known tag's name (asserted)
   * @return the visible entry, or null if the tag is absent or removed by a tombstone
   */
  @Nullable
  public Entry getEntry(String tag) {
    assert KnownTagCodec.keyOf(tag) == 0 : "'" + tag + "' is a known tag; look it up by its id";
    return this.get(tag, Entry.bucketHash(Entry.customHash(tag)));
  }

  private Entry get(String tag, int hash) {
    for (HashStore level = this; level != null; level = level.parent) {
      Entry local = HashSupport.find(level.buckets, hash, tag);
      if (local != null) {
        return local;
      }
      if (level.removedFromParent != null && level.removedFromParent.contains(tag)) {
        return null; // tombstoned: removed locally, do not read through
      }
    }
    return null;
  }

  /**
   * Inserts or replaces a custom tag, without reading through to the parent chain; a tombstone for
   * the tag is cleared, so the new value is visible.
   *
   * @param tag the custom tag's name; must not be a known tag's name
   * @param value the value; never null, since the owner calls {@link #remove} for a null value
   */
  public void store(String tag, @Nonnull Object value) {
    this.put(Entry.newAnyEntry(tag, value));
  }

  /** {@link #store(String, Object)} for a {@link CharSequence}. */
  public void store(String tag, @Nonnull CharSequence value) {
    this.put(Entry.newObjectEntry(tag, value));
  }

  /** {@link #store(String, Object)} for a {@code boolean}, kept unboxed. */
  public void store(String tag, boolean value) {
    this.put(Entry.newBooleanEntry(tag, value));
  }

  /** {@link #store(String, Object)} for an {@code int}, kept unboxed. */
  public void store(String tag, int value) {
    this.put(Entry.newIntEntry(tag, value));
  }

  /** {@link #store(String, Object)} for a {@code long}, kept unboxed. */
  public void store(String tag, long value) {
    this.put(Entry.newLongEntry(tag, value));
  }

  /** {@link #store(String, Object)} for a {@code float}, kept unboxed. */
  public void store(String tag, float value) {
    this.put(Entry.newFloatEntry(tag, value));
  }

  /** {@link #store(String, Object)} for a {@code double}, kept unboxed. */
  public void store(String tag, double value) {
    this.put(Entry.newDoubleEntry(tag, value));
  }

  /**
   * Inserts or replaces a custom tag's entry. Does not consult the read-through parent.
   *
   * @param newEntry the custom tag's entry
   * @return the local entry it replaced, or null if there was none
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

  /**
   * {@link #put}, also reporting the value the put hid.
   *
   * @param newEntry the custom tag's entry
   * @return the prior visible entry -- local, else read through -- or null if the tag was not
   *     visible (absent from the chain, or removed by a tombstone)
   */
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
   * Removes a custom tag. Removing a key the parent chain still exposes also installs a tombstone,
   * so it stops reading through.
   *
   * @param tag the custom tag's name; must not be a known tag's name (asserted)
   * @return the prior visible entry, or null if the tag was not visible
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
   * Checks whether an ancestor's entry shows through this leaf.
   *
   * @param parentEntry an entry held by {@code fromAncestor}
   * @param fromAncestor the ancestor level holding {@code parentEntry}
   * @return whether no nearer level shadows or tombstones the entry's tag
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

  /**
   * Visits the local entries, then the visible inherited ones.
   *
   * @param consumer receives each visible entry
   */
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

  /**
   * {@link #forEach(Object, BiConsumer)} over the local entries only, skipping the parent chain.
   *
   * @param thisObj passed as the consumer's first argument
   * @param consumer receives {@code thisObj} and each local entry
   */
  <T> void forEachLocal(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
    HashSupport.forEach(this.buckets, thisObj, consumer);
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
   * Copies this store.
   *
   * @return an independently mutable copy with the same (frozen, shared) parent, this store's own
   *     entries (shared Entry objects) and its tombstones
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

  /**
   * Copies the entries of a parentless store, sharing Entry objects. "Local" names the source: only
   * {@code that}'s own entries are copied, since it has no parent.
   *
   * @param that the store to copy from; must have no parent (asserted)
   */
  void putAllLocal(HashStore that) {
    assert that.parent == null : "putAllLocal copies only a parentless store";
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

  /**
   * Puts the local entries into a map, for a store with no parent (asserted).
   *
   * @param map receives each entry's tag name and value
   */
  void fillMapLocal(Map<? super String, Object> map) {
    assert this.parent == null : "fillMapLocal is for a parentless store";
    HashSupport.fillMap(this.buckets, map);
  }

  /**
   * {@link #fillMapLocal} with each value as a String.
   *
   * @param map receives each entry's tag name and string value
   */
  void fillStringMapLocal(Map<? super String, ? super String> map) {
    assert this.parent == null : "fillStringMapLocal is for a parentless store";
    HashSupport.fillStringMap(this.buckets, map);
  }

  /**
   * Tests only: checks the store's structure.
   *
   * @throws IllegalStateException if the structure is inconsistent
   */
  @VisibleForTesting
  void checkIntegrity() {
    HashSupport.checkBuckets(this.buckets);
    if (this.count != this.computeSize()) {
      throw new IllegalStateException("incorrect size");
    }
  }

  /**
   * Tests only: recounts the local entries from the structure.
   *
   * @return the local count
   */
  @VisibleForTesting
  int computeSize() {
    return HashSupport.count(this.buckets);
  }

  /**
   * Dumps the internal structure, for deep debugging.
   *
   * @return a description of the store's internal layout
   */
  String toInternalString() {
    return HashSupport.toInternalString(this.buckets);
  }
}

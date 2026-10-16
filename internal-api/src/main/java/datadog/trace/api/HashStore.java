package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.BucketGroup;
import datadog.trace.api.TagMap.Entry;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * TagMap's hash store: a fixed-size bucket array of shareable, immutable {@link Entry} objects,
 * keyed by the 64-bit tag hash (a known tag's id, or a custom tag's name hash in the low 32 bits;
 * the two never collide, see {@link Entry#tagHashOf}).
 *
 * <p>It is the name store, holding the custom tags by name. Through the tag-hash scheme it can hold
 * known tags too, by id: in hash mode it holds both, and {@link #idStore()} adapts its known-tag
 * half to the {@link IdStore} API. Other IdStore implementations (a dense store) are separate
 * classes. No locking: guarded by the owner's lock.
 *
 * <p>Owned by a {@link TagMap}, or directly by a span in the tracer core, which routes to it with
 * {@link TagRouting}. Name-keyed methods take only custom tag names: a known tag is resolved to its
 * id first, and goes through the id-keyed methods.
 *
 * <p>For memory efficiency, a bucket holds a lone Entry directly. Because Entries are shared
 * between maps, they cannot form a linked list to handle collisions; colliding entries share a
 * fixed-size {@link BucketGroup} instead, chained when one fills. Removals are assumed rare, so
 * groups are never consolidated, but an emptied group is unlinked from its chain.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public final class HashStore {
  final Object[] buckets;
  int size;

  /**
   * Optional frozen parent store for read-through. When non-null, reads that miss the local buckets
   * fall through to the parent chain, nearest-level-wins (a local entry shadows the parent's, a
   * nearer ancestor shadows a farther one). The chain mirrors the owning TagMaps' parents. Not
   * final only so {@link #clear()} can detach it (to null); it is otherwise fixed at construction
   * and never re-pointed.
   */
  HashStore parent;

  /**
   * Parent keys removed locally (read-through tombstones), by canonical name. Lazily allocated on
   * the first such removal; {@code null} both means "no tombstones" and serves as the gate that
   * keeps the hot paths untouched. Only meaningful when {@link #parent} != null. A tombstone stops
   * read-through fall-through for its key, so a key removed from a child no longer reads through to
   * the parent. Kept off the bucket structure deliberately -- it is shape-agnostic (bare-Entry vs
   * BucketGroup) and rare, so it costs a lazy allocation on removal rather than complicating the
   * hot bucket code.
   */
  private Set<String> removedFromParent;

  /** A fresh, empty store with no parent. */
  public HashStore() {
    this((HashStore) null);
  }

  /**
   * A fresh, empty store reading through to {@code parent} (may be null), which must be frozen --
   * see {@link TagMap#readThroughStore}.
   */
  public HashStore(HashStore parent) {
    // needs to be a power of 2 for bucket masking calculation to work as intended
    this.buckets = new Object[1 << 4];
    this.size = 0;
    this.parent = parent;
  }

  /** A parentless store over {@code buckets}; reads no statics, so TagMap.EMPTY can use it. */
  HashStore(Object[] buckets, int size) {
    this.buckets = buckets;
    this.size = size;
    this.parent = null;
  }

  /** Exact count, including the visible part of the read-through chain. */
  int size() {
    HashStore parent = this.parent;
    return parent == null ? this.size : this.size + this.visibleParentCount();
  }

  /**
   * Exact count of ancestor entries not shadowed by a nearer level or tombstoned (the read-through
   * addition). Walks the ancestor chain iteratively, nearest-level-wins.
   */
  private int visibleParentCount() {
    int count = 0;
    for (HashStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          if (parentEntryVisible((Entry) parentBucket, ancestor)) count++;
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor)) count++;
            }
          }
        }
      }
    }
    return count;
  }

  boolean isEmpty() {
    if (this.size != 0) {
      return false;
    }
    HashStore parent = this.parent;
    if (parent == null) {
      return true;
    }
    if (this.removedFromParent == null) {
      // A live parent is never definitely-empty: TagMap.createFromParent drops an empty parent (and
      // copy() only forwards an already-vetted parent), and the parent is frozen so it can't become
      // empty. So with no local entries and no tombstones, the parent contributes at least one
      // visible entry -> this store is non-empty.
      return false;
    }
    // size == 0 with tombstones (rare): empty iff every visible ancestor entry is tombstoned
    return this.visibleParentCount() == 0;
  }

  /** Cheap: empty iff no level in the chain holds a local entry (ignores shadowing/tombstones). */
  boolean isDefinitelyEmpty() {
    for (HashStore level = this; level != null; level = level.parent) {
      if (level.size != 0) {
        return false;
      }
    }
    return true;
  }

  /** Upper bound: sum of every level's local size, ignoring read-through shadowing/removals. */
  int estimateSize() {
    int total = 0;
    for (HashStore level = this; level != null; level = level.parent) {
      total += level.size;
    }
    return total;
  }

  /** A new {@link IdStore} over this store's known tags. */
  public IdStore idStore() {
    return new HashIdStore(this);
  }

  /*
   * Known tags, by id.
   */

  /**
   * The known tag {@code tagId}'s entry, reading through to the parent chain.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public Entry getById(long tagId) {
    return this.get(Entry.requireKnownName(tagId), tagId);
  }

  /** {@link #getById}'s value, boxed if necessary, or null. */
  public Object getKnownValue(long tagId) {
    Entry entry = this.getById(tagId);
    return entry == null ? null : entry.objectValue();
  }

  /**
   * Removes the known tag {@code tagId}, returning the prior visible entry.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public Entry removeById(long tagId) {
    return this.remove(Entry.requireKnownName(tagId), tagId);
  }

  /*
   * Sets a known tag by id without validating it: the caller has checked KnownTagCodec.isKnown.
   */

  public void setKnown(long tagId, Object value) {
    this.put(new Entry(tagId, null, Entry.ANY, 0L, value));
  }

  public void setKnown(long tagId, CharSequence value) {
    this.put(new Entry(tagId, null, EntryReader.OBJECT, 0L, value));
  }

  public void setKnown(long tagId, boolean value) {
    this.put(
        new Entry(
            tagId, null, EntryReader.BOOLEAN, Entry.boolean2Prim(value), Boolean.valueOf(value)));
  }

  public void setKnown(long tagId, int value) {
    this.put(new Entry(tagId, null, EntryReader.INT, Entry.int2Prim(value), null));
  }

  public void setKnown(long tagId, long value) {
    this.put(new Entry(tagId, null, EntryReader.LONG, Entry.long2Prim(value), null));
  }

  public void setKnown(long tagId, float value) {
    this.put(new Entry(tagId, null, EntryReader.FLOAT, Entry.float2Prim(value), null));
  }

  public void setKnown(long tagId, double value) {
    this.put(new Entry(tagId, null, EntryReader.DOUBLE, Entry.double2Prim(value), null));
  }

  /*
   * Custom tags, by name (the name-store role). The name must not be a known tag's: a caller
   * resolves a name once and routes a known tag by its id instead (see TagRouting).
   */

  /** The custom tag {@code tag}'s entry, reading through to the parent chain. */
  public Entry get(String tag) {
    assert KnownTagCodec.keyOf(tag) == 0 : "'" + tag + "' is a known tag; look it up by its id";
    return this.get(tag, Entry.customHash(tag));
  }

  /** Removes the custom tag {@code tag}; see {@link #remove(String, long)}. */
  public Entry remove(String tag) {
    assert KnownTagCodec.keyOf(tag) == 0 : "'" + tag + "' is a known tag; remove it by its id";
    return this.remove(tag, Entry.customHash(tag));
  }

  /*
   * Sets the custom tag {@code tag}, which must not be a known tag's name.
   */

  public void set(String tag, Object value) {
    this.put(Entry.newAnyEntry(tag, value));
  }

  public void set(String tag, CharSequence value) {
    this.put(Entry.newObjectEntry(tag, value));
  }

  public void set(String tag, boolean value) {
    this.put(Entry.newBooleanEntry(tag, value));
  }

  public void set(String tag, int value) {
    this.put(Entry.newIntEntry(tag, value));
  }

  public void set(String tag, long value) {
    this.put(Entry.newLongEntry(tag, value));
  }

  public void set(String tag, float value) {
    this.put(Entry.newFloatEntry(tag, value));
  }

  public void set(String tag, double value) {
    this.put(Entry.newDoubleEntry(tag, value));
  }

  /*
   * Either kind, by the entry's own identity (its canonical name and tag hash).
   */

  /** The entry for {@code (canonicalTag, tagHash)}: local first, then the read-through chain. */
  private Entry get(String canonicalTag, long tagHash) {
    Entry local = this.getLocal(canonicalTag, tagHash);
    if (local != null) {
      // Local entry shadows the parent (local-wins) -- unchanged hot path.
      return local;
    }
    // Read-through: miss locally, defer to the frozen parent.
    // The tombstone check lives only here, on the cold miss+parent path -- the hot local hit above
    // never touches it.
    HashStore parent = this.parent;
    if (parent == null) {
      return null;
    }
    if (this.removedFromParent != null && this.removedFromParent.contains(canonicalTag)) {
      return null; // tombstoned: removed locally, do not read through
    }
    return parent.get(canonicalTag, tagHash);
  }

  /**
   * Inserts or replaces a local entry, returning the replaced local Entry (or null if none). Does
   * NOT consult the read-through parent -- set paths use this so they never pay for a prior-value
   * lookup they discard; {@link #getAndPut(Entry)} layers the parent fallback on top. Takes either
   * kind of entry: the entry already carries its id or its name.
   */
  public Entry put(Entry newEntry) {
    // Re-setting a key clears any read-through tombstone for it (the new value overrides the
    // removal). Gated on the lazy field, so this is a no-op for the common no-tombstone case.
    if (this.removedFromParent != null) {
      this.removedFromParent.remove(newEntry.tag);
    }

    Object[] thisBuckets = this.buckets;

    int newHash = newEntry.hash();
    int bucketIndex = newHash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    if (bucket == null) {
      thisBuckets[bucketIndex] = newEntry;

      this.size += 1;
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

        this.size += 1;
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
      this.size += 1;
      return null;
    }

    // unreachable
    return null;
  }

  /** {@link #put(Entry)}, returning the prior visible entry -- local, else read through. */
  public Entry getAndPut(Entry newEntry) {
    // Capture whether the key was tombstoned BEFORE put clears it: a tombstoned key had no visible
    // prior value (it was removed), so getAndPut must report null rather than the parent's.
    boolean wasTombstoned =
        this.removedFromParent != null && this.removedFromParent.contains(newEntry.tag);

    Entry priorLocal = this.put(newEntry);
    if (priorLocal != null) {
      return priorLocal; // replaced a local entry -> that is the prior value
    }
    // No local entry was replaced. The prior visible value, if any, was the parent's -- unless the
    // key was tombstoned (then it was not visible).
    if (wasTombstoned || this.parent == null) {
      return null;
    }
    // By the entry's own identity, not its name: the name would only resolve back to the same hash.
    return this.parent.get(newEntry.tag, newEntry.tagHash);
  }

  /**
   * Removes {@code (canonicalTag, tagHash)}, returning the prior visible entry. Removing a key the
   * parent chain still exposes also installs a tombstone, so it stops reading through.
   */
  private Entry remove(String canonicalTag, long tagHash) {
    Entry localRemoved = this.removeLocal(canonicalTag, tagHash);

    HashStore parent = this.parent;
    if (parent != null) {
      // Read-through: if the parent still exposes this key, removing it must also hide it from
      // fall-through -- install a tombstone. The prior *visible* value (Map.remove contract) is the
      // local entry if there was one, otherwise the parent's (which we now hide). Rare path (only
      // when removing a parent-exposed key).
      boolean alreadyTombstoned =
          this.removedFromParent != null && this.removedFromParent.contains(canonicalTag);
      if (!alreadyTombstoned) {
        Entry parentEntry = parent.get(canonicalTag, tagHash);
        if (parentEntry != null) {
          if (this.removedFromParent == null) {
            // Small initial capacity: this set is rare and almost always holds only a handful of
            // tombstoned keys, so the default 16-bucket HashSet table would be wasteful.
            this.removedFromParent = new HashSet<>(4);
          }
          this.removedFromParent.add(canonicalTag);
          return localRemoved != null ? localRemoved : parentEntry;
        }
      }
    }
    return localRemoved;
  }

  /**
   * Whether an entry that lives at ancestor level {@code fromAncestor} is visible through this
   * leaf: not shadowed and not tombstoned by any nearer level (the leaf, or a closer ancestor).
   * Nearest-level-wins. This mirrors what {@link #get(String, long)}'s recursion applies as it
   * descends -- each nearer level contributes both its own local entries (shadowing) and its own
   * read-through removals (tombstones). A non-leaf level can carry tombstones too: a map may remove
   * an inherited key and then itself be frozen and reused as a parent. Exploits universal hashing
   * -- the only local entry that could shadow {@code parentEntry} at a level is in that level's
   * same-index bucket, so each nearer level is probed at one bucket using {@code parentEntry}'s
   * cached hash (no re-hash, no full-map probe).
   */
  boolean parentEntryVisible(Entry parentEntry, HashStore fromAncestor) {
    int hash = parentEntry.hash();
    String tag = parentEntry.tag;
    for (HashStore nearer = this; nearer != fromAncestor; nearer = nearer.parent) {
      // tombstoned by a nearer level (its own read-through removal hides the deeper entry)
      if (nearer.removedFromParent != null && nearer.removedFromParent.contains(tag)) {
        return false;
      }
      // shadowed by a nearer level's local entry
      Object[] nearerBuckets = nearer.buckets;
      Object nearerBucket = nearerBuckets[hash & (nearerBuckets.length - 1)];
      if (findInBucket(nearerBucket, hash, tag) != null) {
        return false;
      }
    }
    return true;
  }

  /** Looks up an entry in this store's own buckets only -- no read-through to the parent. */
  private Entry getLocal(String tag, long tagHash) {
    Object[] thisBuckets = this.buckets;
    int hash = Entry.bucketHash(tagHash);
    return findInBucket(thisBuckets[hash & (thisBuckets.length - 1)], hash, tag);
  }

  /**
   * Finds an entry by hash/tag within a single bucket object (Entry | BucketGroup chain | null).
   */
  private static Entry findInBucket(Object bucket, int hash, String tag) {
    if (bucket instanceof Entry) {
      Entry tagEntry = (Entry) bucket;
      return tagEntry.hash() == hash && tagEntry.matches(tag) ? tagEntry : null;
    } else if (bucket instanceof BucketGroup) {
      return ((BucketGroup) bucket).findInChain(hash, tag);
    }
    return null;
  }

  /** Removes an entry from this store's own buckets only -- no parent/tombstone handling. */
  private Entry removeLocal(String tag, long tagHash) {
    Object[] thisBuckets = this.buckets;

    int hash = Entry.bucketHash(tagHash);
    int bucketIndex = hash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    // null bucket case - do nothing
    if (bucket instanceof Entry) {
      Entry existingEntry = (Entry) bucket;
      if (existingEntry.hash() == hash && existingEntry.matches(tag)) {
        thisBuckets[bucketIndex] = null;

        this.size -= 1;
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

      this.size -= 1;
      return existingEntry;
    }
    return null;
  }

  /**
   * An independently mutable copy: the same (frozen, shared) parent, this store's own entries
   * (shared Entry objects) and its tombstones.
   */
  HashStore copy() {
    HashStore copy = new HashStore(this.parent);
    copy.putAllIntoEmpty(this);
    if (this.removedFromParent != null) {
      copy.removedFromParent = new HashSet<>(this.removedFromParent);
    }
    return copy;
  }

  /**
   * Copies {@code that}'s own entries into this store, sharing the Entry objects. Since stores
   * don't support expansion, buckets are perfectly aligned.
   */
  void putAllLocal(HashStore that) {
    if (this.size == 0) {
      this.putAllIntoEmpty(that);
    } else {
      this.putAllMerge(that);
    }
  }

  private void putAllMerge(HashStore that) {
    Object[] thisBuckets = this.buckets;
    Object[] thatBuckets = that.buckets;

    // Since TagMap-s don't support expansion, buckets are perfectly aligned
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
          this.size += 1;
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          BucketGroup thisNewGroup = thatGroup.cloneChain();
          thisBuckets[i] = thisNewGroup;
          this.size += thisNewGroup.sizeInChain();
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
            this.size += 1;
          }
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          // Clone the other group, then place this entry into that group
          BucketGroup thisNewGroup = thatGroup.cloneChain();
          int thisNewGroupSize = thisNewGroup.sizeInChain();

          Entry incomingEntry = thisNewGroup.findInChain(thisHash, thisEntry.tag());
          if (incomingEntry != null) {
            // there's already an entry w/ the same tag from the incoming TagMap
            // incoming entry clobbers the existing try, so we're done
            thisBuckets[i] = thisNewGroup;

            // overlapping group - subtract one for clobbered existing entry
            this.size += thisNewGroupSize - 1;
          } else if (thisNewGroup.insertInChain(thisHash, thisEntry)) {
            // able to add thisEntry into the existing groups
            thisBuckets[i] = thisNewGroup;

            // non overlapping group - existing entry already accounted for in this.size
            this.size += thisNewGroupSize;
          } else {
            // unable to add into the existing groups
            thisBuckets[i] = new BucketGroup(thisHash, thisEntry, thisNewGroup);

            // non overlapping group - existing entry already accounted for in this.size
            this.size += thisNewGroupSize;
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
            this.size += 1;
          } else {
            thisBuckets[i] = new BucketGroup(thatHash, thatEntry, thisGroup);
            this.size += 1;
          }
        } else if (thatBucket instanceof BucketGroup) {
          // Most complicated case - need to walk that bucket group chain and update this chain
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          // Taking the easy / expensive way out for updating size
          int thisPrevGroupSize = thisGroup.sizeInChain();

          BucketGroup thisNewGroup = thisGroup.replaceOrInsertAllInChain(thatGroup);
          int thisNewGroupSize = thisNewGroup.sizeInChain();

          thisBuckets[i] = thisNewGroup;
          this.size += (thisNewGroupSize - thisPrevGroupSize);
        }
      }
    }
  }

  /*
   * Specially optimized version of putAll for the common case of destination store being empty
   */
  void putAllIntoEmpty(HashStore that) {
    Object[] thisBuckets = this.buckets;
    Object[] thatBuckets = that.buckets;

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
    this.size = that.size;
  }

  void fillMapLocal(Map<? super String, Object> map) {
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

  void fillStringMapLocal(Map<? super String, ? super String> stringMap) {
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

  /** Visits the local entries, then the visible read-through entries. */
  void forEach(Consumer<? super EntryReader> consumer) {
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

    // read-through: parent entries not shadowed locally or tombstoned. Kept out of line so the
    // common parent == null path stays small / inlinable.
    if (this.parent != null) {
      this.forEachParent(consumer);
    }
  }

  private void forEachParent(Consumer<? super EntryReader> consumer) {
    // Walk the ancestor chain, nearest first. Each entry is emitted once, by the nearest level that
    // defines its key, when not shadowed by a nearer level and not tombstoned.
    for (HashStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          Entry parentEntry = (Entry) parentBucket;
          if (parentEntryVisible(parentEntry, ancestor)) consumer.accept(parentEntry);
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor))
                consumer.accept(parentEntry);
            }
          }
        }
      }
    }
  }

  <T> void forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
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

    // read-through: parent entries not shadowed locally or tombstoned (kept out of line).
    if (this.parent != null) {
      this.forEachParent(thisObj, consumer);
    }
  }

  private <T> void forEachParent(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
    for (HashStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          Entry parentEntry = (Entry) parentBucket;
          if (parentEntryVisible(parentEntry, ancestor)) consumer.accept(thisObj, parentEntry);
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor)) {
                consumer.accept(thisObj, parentEntry);
              }
            }
          }
        }
      }
    }
  }

  <T, U> void forEach(T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
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

    // read-through: parent entries not shadowed locally or tombstoned (kept out of line).
    if (this.parent != null) {
      this.forEachParent(thisObj, otherObj, consumer);
    }
  }

  private <T, U> void forEachParent(
      T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    for (HashStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          Entry parentEntry = (Entry) parentBucket;
          if (parentEntryVisible(parentEntry, ancestor))
            consumer.accept(thisObj, otherObj, parentEntry);
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor)) {
                consumer.accept(thisObj, otherObj, parentEntry);
              }
            }
          }
        }
      }
    }
  }

  /**
   * Removes every entry, including any inherited through read-through. Detaching the parent (rather
   * than tombstoning every inherited key) is simpler and cheaper, and leaves an empty, parent-less
   * store. Detach is one-way -- the parent is never re-pointed.
   */
  void clear() {
    Arrays.fill(this.buckets, null);
    this.size = 0;
    this.parent = null;
    this.removedFromParent = null;
  }

  void checkIntegrity() {
    // Decided to use if ( cond ) throw new IllegalStateException rather than assert
    // That was done to avoid the extra static initialization needed for an assertion
    // While that's probably an unnecessary optimization, this method is only called in tests

    Object[] thisBuckets = this.buckets;

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
            Entry thisEntry = curGroup._entryAt(i);
            if (thisEntry == null) continue;

            int thisHash = thisEntry.hash();
            assert curGroup._hashAt(i) == thisHash;

            int expectedBucket = thisHash & (thisBuckets.length - 1);
            if (expectedBucket != i) {
              throw new IllegalStateException("incorrect bucket");
            }
          }
        }
      }
    }

    if (this.size != this.computeSize()) {
      throw new IllegalStateException("incorrect size");
    }
    // Local-structure invariant: the size counter's emptiness must match the local buckets.
    if ((this.size == 0) != this.checkIfEmpty()) {
      throw new IllegalStateException("incorrect empty status");
    }
  }

  int computeSize() {
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

  boolean checkIfEmpty() {
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

  /** The bucket layout, for deep debugging. */
  String toInternalString() {
    Object[] thisBuckets = this.buckets;

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
}

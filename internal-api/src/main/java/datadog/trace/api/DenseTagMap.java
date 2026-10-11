package datadog.trace.api;

import datadog.trace.api.function.TriConsumer;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * The dense/hash hybrid {@link TagMap}: known tags are stored densely, by id, with no per-tag
 * {@link TagMap.Entry}; custom tags stay in the buckets. Used when the dense store is on; see
 * {@link TagMapFactory}.
 *
 * <p>Each routing method handles a dense id here and defers everything else to {@link TagMap}'s
 * hash-only behavior. Every map in a JVM is of the one implementation, so a parent is always a
 * {@code DenseTagMap} too.
 */
final class DenseTagMap extends TagMap {

  /*
   * Dense known-tag store (experimental; see TagMap.DenseStore.ENABLED, default off).
   * <p>
   * With the flag on, a known tag that has a dense-store slot (KnownTagCodec.hasSlot -- the
   * registry colors required/conditional/recommended tags into slots so tags on one span type never
   * share one) is NOT stored as a bucket Entry. Its value lives in the insertion-ordered parallel
   * arrays knownIds/knownValues instead, with no per-tag Entry object: that is the allocation win.
   * Known tags without a slot (NO_SLOT) and custom tags stay in the buckets, exactly as before.
   * <p>
   * Presence: knownOccupancy holds 1L << slot for every dense id ever added. A clear bit means the
   * tag is definitely absent, so an append or a miss skips the scan. A set bit only means "maybe":
   * the knownIds scan is authoritative, so two tags sharing a slot in one map (legal -- tags from
   * span types that never co-occur can still both be set) still work. Bits are never cleared on
   * remove; a stale bit costs a scan, never a wrong answer. knownTraceLevel records whether any
   * trace-level id was added (trace and span tags reuse slot numbers), with the same superset rule.
   * <p>
   * Disjointness: whether an id routes dense depends only on the id and the global flag, so in a
   * given JVM a tag is always dense or always bucketed -- never both, in any map. Read-through
   * therefore shadows within a region: an ancestor's dense value can only be shadowed by a nearer
   * level's dense value of the same id (parentDenseVisible), an ancestor's bucket entry only by a
   * nearer bucket entry (parentEntryVisible, unchanged). Tombstones (removedFromParent) are keyed by
   * canonical name and apply to both regions.
   * <p>
   * size counts bucket entries only; knownCount counts dense entries; the local total is
   * size + knownCount. Buckets start on the shared EMPTY_BUCKETS and are copied on the first bucket write, so an all-dense map allocates no bucket array.
   * <p>
   * Iteration (iterator(), forEach) emits a dense entry through a reused EntryReadingHelper
   * flyweight: valid only until the next element, so call .entry() to retain one. Point reads
   * (getEntry) materialize a fresh Entry.
   */

  /** Shared all-null buckets (length 16) that maps start on. */
  private static final Object[] EMPTY_BUCKETS = new Object[1 << 4];

  /** Initial capacity of the dense arrays; the largest span type resolves about this many tags. */
  private static final int KNOWN_INIT_CAP = 12;

  /**
   * Shared empty dense arrays a map starts on (and returns to on clear), so that one full-capacity
   * test covers a map's first dense tag too. Never written: a full map spills to its own arrays.
   */
  private static final long[] EMPTY_KNOWN_IDS = new long[0];

  private static final Object[] EMPTY_KNOWN_VALUES = new Object[0];

  private long[] knownIds = EMPTY_KNOWN_IDS;
  private Object[] knownValues = EMPTY_KNOWN_VALUES;
  private int knownCount;
  private long knownOccupancy;
  private boolean knownTraceLevel;

  DenseTagMap(TagMap parent) {
    // start on the shared empty buckets; the first bucket write copies them
    super(parent, EMPTY_BUCKETS);
  }

  DenseTagMap(Object[] buckets, int size) {
    super(buckets, size);
  }

  @Override
  int knownCount() {
    return this.knownCount;
  }

  @Override
  int countDenseVisibleIn(TagMap ancestor) {
    DenseTagMap dense = (DenseTagMap) ancestor;
    long[] ancestorIds = dense.knownIds;
    int count = 0;
    for (int i = 0; i < dense.knownCount; ++i) {
      if (this.parentDenseVisible(ancestorIds[i], dense)) count++;
    }
    return count;
  }

  @Override
  Entry getLocalEntry(String tag, long tagHash) {
    long denseId = denseIdOf(tagHash);
    if (denseId != 0) {
      int i = this.knownIndexOf(denseId);
      return i < 0 ? null : Entry.newAnyEntry(denseId, this.knownValues[i]);
    }
    return super.getLocalEntry(tag, tagHash);
  }

  @Override
  Object getKnownValue(long tagId) {
    int i = this.knownIndexOf(tagId);
    return i >= 0 ? this.knownValues[i] : super.getKnownValue(tagId);
  }

  /** The buckets to write to, copying the shared empty buckets on the first write. */
  @Override
  Object[] writableBuckets() {
    Object[] b = this.buckets;
    if (b == EMPTY_BUCKETS) {
      b = new Object[1 << 4];
      this.buckets = b;
    }
    return b;
  }

  @Override
  Entry putEntry(@Nonnull Entry newEntry) {
    long denseId = denseIdOf(newEntry.tagHash);
    if (denseId != 0) {
      return this.putDenseLocal(denseId, newEntry.objectValue());
    }
    return super.putEntry(newEntry);
  }

  @Override
  public void set(long tagId, @Nonnull Object value) {
    this.storeDenseLocal(requireKnownId(tagId), value);
  }

  @Override
  public void set(long tagId, @Nonnull CharSequence value) {
    this.storeDenseLocal(requireKnownId(tagId), value);
  }

  @Override
  public void set(long tagId, boolean value) {
    this.storeDenseLocal(requireKnownId(tagId), Boolean.valueOf(value));
  }

  @Override
  public void set(long tagId, int value) {
    this.storeDenseLocal(requireKnownId(tagId), Integer.valueOf(value));
  }

  @Override
  public void set(long tagId, long value) {
    this.storeDenseLocal(requireKnownId(tagId), Long.valueOf(value));
  }

  @Override
  public void set(long tagId, float value) {
    this.storeDenseLocal(requireKnownId(tagId), Float.valueOf(value));
  }

  @Override
  public void set(long tagId, double value) {
    this.storeDenseLocal(requireKnownId(tagId), Double.valueOf(value));
  }

  @Override
  void setKnownTrusted(long tagId, @Nonnull Object value) {
    this.storeDenseTrusted(tagId, value);
  }

  @Override
  void setKnownTrusted(long tagId, @Nonnull CharSequence value) {
    this.storeDenseTrusted(tagId, value);
  }

  @Override
  void setKnownTrusted(long tagId, boolean value) {
    this.storeDenseTrusted(tagId, Boolean.valueOf(value));
  }

  @Override
  void setKnownTrusted(long tagId, int value) {
    this.storeDenseTrusted(tagId, Integer.valueOf(value));
  }

  @Override
  void setKnownTrusted(long tagId, long value) {
    this.storeDenseTrusted(tagId, Long.valueOf(value));
  }

  @Override
  void setKnownTrusted(long tagId, float value) {
    this.storeDenseTrusted(tagId, Float.valueOf(value));
  }

  @Override
  void setKnownTrusted(long tagId, double value) {
    this.storeDenseTrusted(tagId, Double.valueOf(value));
  }

  @Override
  void putAllMerge(TagMap that) {
    DenseTagMap dense = (DenseTagMap) that;
    // incoming dense values clobber existing ones, as incoming bucket entries do
    for (int i = 0; i < dense.knownCount; ++i) {
      this.storeKnownValue(dense.knownIds[i], dense.knownValues[i]);
    }
    super.putAllMerge(that);
  }

  @Override
  void putAllIntoEmptyMap(TagMap that) {
    DenseTagMap dense = (DenseTagMap) that;
    if (dense.knownCount > 0) {
      // dense values are immutable objects, so the copy can share them
      this.knownIds = Arrays.copyOf(dense.knownIds, dense.knownIds.length);
      this.knownValues = Arrays.copyOf(dense.knownValues, dense.knownValues.length);
      this.knownCount = dense.knownCount;
      this.knownOccupancy = dense.knownOccupancy;
      this.knownTraceLevel = dense.knownTraceLevel;
    }
    if (that.size == 0) {
      return; // no bucket entries; stay on the shared empty buckets
    }
    super.putAllIntoEmptyMap(that);
  }

  @Override
  public void fillMap(Map<? super String, Object> map) {
    super.fillMap(map);
    if (this.parent == null) { // with a parent, super walked the visible union, dense included
      for (int i = 0; i < this.knownCount; ++i) {
        map.put(KnownTagCodec.nameOf(this.knownIds[i]), this.knownValues[i]);
      }
    }
  }

  @Override
  public void fillStringMap(Map<? super String, ? super String> stringMap) {
    super.fillStringMap(stringMap);
    if (this.parent == null) { // see fillMap
      for (int i = 0; i < this.knownCount; ++i) {
        stringMap.put(
            KnownTagCodec.nameOf(this.knownIds[i]),
            TagValueConversions.toString(this.knownValues[i]));
      }
    }
  }

  @Override
  Entry removeLocal(String tag, long tagHash) {
    long denseId = denseIdOf(tagHash);
    if (denseId != 0) {
      return this.removeKnown(denseId);
    }
    return super.removeLocal(tag, tagHash);
  }

  @Override
  public void forEach(Consumer<? super TagMap.EntryReader> consumer) {
    if (this.knownCount > 0) {
      // dense values go through one reused flyweight -- no Entry per tag
      EntryReadingHelper reader = new EntryReadingHelper();
      for (int i = 0; i < this.knownCount; ++i) {
        reader.set(this.knownIds[i], this.knownValues[i]);
        consumer.accept(reader);
      }
    }
    super.forEach(consumer);
  }

  @Override
  public <T> void forEach(T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
    if (this.knownCount > 0) {
      EntryReadingHelper reader = new EntryReadingHelper();
      for (int i = 0; i < this.knownCount; ++i) {
        reader.set(this.knownIds[i], this.knownValues[i]);
        consumer.accept(thisObj, reader);
      }
    }
    super.forEach(thisObj, consumer);
  }

  @Override
  public <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.EntryReader> consumer) {
    if (this.knownCount > 0) {
      EntryReadingHelper reader = new EntryReadingHelper();
      for (int i = 0; i < this.knownCount; ++i) {
        reader.set(this.knownIds[i], this.knownValues[i]);
        consumer.accept(thisObj, otherObj, reader);
      }
    }
    super.forEach(thisObj, otherObj, consumer);
  }

  @Override
  void forEachDenseVisibleIn(TagMap ancestor, Consumer<? super TagMap.EntryReader> consumer) {
    DenseTagMap dense = (DenseTagMap) ancestor;
    EntryReadingHelper reader = null;
    for (int i = 0; i < dense.knownCount; ++i) {
      long denseId = dense.knownIds[i];
      if (this.parentDenseVisible(denseId, dense)) {
        if (reader == null) reader = new EntryReadingHelper();
        reader.set(denseId, dense.knownValues[i]);
        consumer.accept(reader);
      }
    }
  }

  @Override
  <T> void forEachDenseVisibleIn(
      TagMap ancestor, T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
    DenseTagMap dense = (DenseTagMap) ancestor;
    EntryReadingHelper reader = null;
    for (int i = 0; i < dense.knownCount; ++i) {
      long denseId = dense.knownIds[i];
      if (this.parentDenseVisible(denseId, dense)) {
        if (reader == null) reader = new EntryReadingHelper();
        reader.set(denseId, dense.knownValues[i]);
        consumer.accept(thisObj, reader);
      }
    }
  }

  @Override
  <T, U> void forEachDenseVisibleIn(
      TagMap ancestor,
      T thisObj,
      U otherObj,
      TriConsumer<T, U, ? super TagMap.EntryReader> consumer) {
    DenseTagMap dense = (DenseTagMap) ancestor;
    EntryReadingHelper reader = null;
    for (int i = 0; i < dense.knownCount; ++i) {
      long denseId = dense.knownIds[i];
      if (this.parentDenseVisible(denseId, dense)) {
        if (reader == null) reader = new EntryReadingHelper();
        reader.set(denseId, dense.knownValues[i]);
        consumer.accept(thisObj, otherObj, reader);
      }
    }
  }

  @Override
  boolean readDenseIfVisible(TagMap level, int index, EntryReadingHelper reader) {
    DenseTagMap dense = (DenseTagMap) level;
    long denseId = dense.knownIds[index];
    if (dense == this || this.parentDenseVisible(denseId, dense)) {
      reader.set(denseId, dense.knownValues[index]);
      return true;
    }
    return false;
  }

  @Override
  void clearLocal() {
    this.buckets = EMPTY_BUCKETS; // never write to the shared empty buckets
    this.size = 0;
    this.knownIds = EMPTY_KNOWN_IDS;
    this.knownValues = EMPTY_KNOWN_VALUES;
    this.knownCount = 0;
    this.knownOccupancy = 0L;
    this.knownTraceLevel = false;
  }

  @Override
  void checkIntegrity() {
    // dense store: every id slotted and unique, its slot bit set, the count within bounds
    if (this.knownCount > 0) {
      if (this.knownIds == null || this.knownCount > this.knownIds.length) {
        throw new IllegalStateException("incorrect known count");
      }
      for (int i = 0; i < this.knownCount; ++i) {
        long denseId = this.knownIds[i];
        if (denseIdOf(denseId) == 0) {
          throw new IllegalStateException("dense id without a slot");
        }
        if ((this.knownOccupancy & slotBit(denseId)) == 0) {
          throw new IllegalStateException("dense id missing from occupancy");
        }
        for (int j = i + 1; j < this.knownCount; ++j) {
          if (this.knownIds[j] == denseId) {
            throw new IllegalStateException("duplicate dense id");
          }
        }
      }
    }
    // disjointness: no bucket entry may be one that routes dense
    for (EntryReader entry : this) {
      if (entry instanceof Entry && denseIdOf(((Entry) entry).tagHash) != 0) {
        throw new IllegalStateException("dense-routed tag stored in a bucket: " + entry.tag());
      }
    }
    super.checkIntegrity();
  }

  @Override
  String toInternalString() {
    StringBuilder ledger = new StringBuilder(super.toInternalString());
    for (int i = 0; i < this.knownCount; ++i) {
      ledger
          .append("[dense ")
          .append(i)
          .append("] = {")
          .append(KnownTagCodec.nameOf(this.knownIds[i]))
          .append('=')
          .append(this.knownValues[i])
          .append("}\n");
    }
    return ledger.toString();
  }

  /**
   * The id to store densely for {@code tagHash} (an Entry's tag hash, or a tag id), or 0 for a
   * custom tag, which stays in the buckets. Every known tag is dense, slotted or not, so an
   * id-keyed write never reaches the hash path.
   */
  private static long denseIdOf(long tagHash) {
    // a known tag's hash is its id (upper 32 bits nonzero); a custom tag's upper 32 bits are zero
    return (tagHash >>> 32) != 0 ? tagHash : 0L;
  }

  /**
   * A dense id's occupancy bit. The bit only rules a tag out quickly; the scan decides, so ids may
   * share one. No branch for a tag without a slot: a long shift uses only the low 6 bits of its
   * count, so {@code NO_SLOT} (0xFFFF) lands on bit 63, which the generator reserves (real slots
   * stay below 63) and every unslotted tag shares.
   */
  static long slotBit(long denseId) {
    return 1L << KnownTagCodec.slotOf(denseId);
  }

  /**
   * Index of {@code denseId} in the dense arrays, or -1; skips the scan when its slot bit is clear.
   */
  private int knownIndexOf(long denseId) {
    if ((this.knownOccupancy & slotBit(denseId)) == 0) {
      return -1; // definitely absent
    }
    long[] ids = this.knownIds;
    for (int i = 0; i < this.knownCount; ++i) {
      if (ids[i] == denseId) return i;
    }
    return -1;
  }

  /**
   * {@link #knownIndexOf} for a write: one direction of a shared name also matches the name's
   * undirected tag, which it supersedes in place. Both share a slot, so one occupancy test covers
   * them, and for a constant id the alias folds to a constant (or, for most tags, to the id
   * itself).
   */
  private int knownWriteIndexOf(long denseId) {
    if ((this.knownOccupancy & slotBit(denseId)) == 0) {
      return -1; // definitely absent
    }
    long alias =
        KnownTagCodec.hasSharedName(denseId) ? KnownTagCodec.undirectedIdOf(denseId) : denseId;
    long[] ids = this.knownIds;
    for (int i = 0; i < this.knownCount; ++i) {
      long id = ids[i];
      if (id == denseId || id == alias) return i;
    }
    return -1;
  }

  /**
   * Stores a dense value, replacing it in place when present or appending it, and returns the prior
   * value, or null when there was none. Dense values are never null. Builds no Entry, so a caller
   * that discards the result allocates nothing, whether or not the JIT inlines this.
   */
  private Object storeKnownValue(long denseId, Object value) {
    int i = this.knownWriteIndexOf(denseId);
    if (i >= 0) {
      long[] ids = this.knownIds;
      // a superseded undirected tag is relabeled, and was not this tag's prior value
      Object prior = ids[i] == denseId ? this.knownValues[i] : null;
      ids[i] = denseId;
      this.knownValues[i] = value;
      return prior;
    }
    int idx = this.knownCount;
    if (idx == this.knownIds.length) {
      this.spillKnown();
    }
    this.knownIds[idx] = denseId;
    this.knownValues[idx] = value;
    this.knownCount = idx + 1;
    this.knownOccupancy |= slotBit(denseId);
    this.knownTraceLevel |= KnownTagCodec.isTraceLevel(denseId);
    return null;
  }

  /**
   * {@code tagId}, when it names a known tag; the checked id-keyed setters reject anything else.
   */
  private static long requireKnownId(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw new IllegalArgumentException("not a known tag id: " + Long.toHexString(tagId));
    }
    return tagId;
  }

  /**
   * Makes room for one more dense tag. A map's first dense tag allocates its own arrays -- a plain
   * allocation, since on fresh spans this runs once per span and is compiled inline. A full map
   * grows once, in {@link #growKnown}.
   */
  private void spillKnown() {
    if (this.knownIds.length == 0) {
      this.knownIds = new long[KNOWN_INIT_CAP];
      this.knownValues = new Object[KNOWN_INIT_CAP];
    } else {
      this.growKnown();
    }
  }

  /**
   * Grows full dense arrays once, straight to the hard bound: a map holds each known tag at most
   * once, so {@link KnownTagCodec#serialLimit()} entries always suffice. Rare, so the copying stays
   * out of the write.
   */
  private void growKnown() {
    int newCap = Math.max(KnownTagCodec.serialLimit(), this.knownIds.length << 1);
    this.knownIds = Arrays.copyOf(this.knownIds, newCap);
    this.knownValues = Arrays.copyOf(this.knownValues, newCap);
  }

  /** Clears a read-through tombstone for a dense tag being set; out of line, as it is rare. */
  private void clearDenseTombstone(long denseId) {
    this.removedFromParent.remove(KnownTagCodec.nameOf(denseId));
  }

  /**
   * Local dense write: clears any tombstone for the tag, then stores the value, returning the prior
   * value or null. The {@code set(long, ...)} methods use this; it builds no Entry.
   */
  private Object storeDenseLocal(long denseId, Object value) {
    this.checkWriteAccess();
    return this.storeDenseTrusted(denseId, value);
  }

  /** {@link #storeDenseLocal} without the frozen check, for trusted writes. */
  private Object storeDenseTrusted(long denseId, Object value) {
    if (this.removedFromParent != null) {
      this.clearDenseTombstone(denseId);
    }
    return this.storeKnownValue(denseId, value);
  }

  /** {@link #storeDenseLocal}, returning the prior value as an Entry, per the Map contract. */
  private Entry putDenseLocal(long denseId, Object value) {
    Object prior = this.storeDenseLocal(denseId, value);
    return prior == null ? null : Entry.newAnyEntry(denseId, prior);
  }

  /** Removes a dense value (swap with the last), returning it as an Entry, or null. */
  private Entry removeKnown(long denseId) {
    int i = this.knownIndexOf(denseId);
    if (i < 0) return null;
    Object prior = this.knownValues[i];
    int last = --this.knownCount;
    this.knownIds[i] = this.knownIds[last];
    this.knownValues[i] = this.knownValues[last];
    this.knownIds[last] = 0L;
    this.knownValues[last] = null;
    // knownOccupancy is not cleared: another present id may share the slot bit.
    return Entry.newAnyEntry(denseId, prior);
  }

  /**
   * Whether an ancestor's dense value for {@code denseId} (held at {@code fromAncestor}) is visible
   * from this leaf: not shadowed by a nearer level's dense value of the same id, and not tombstoned
   * by a nearer level. The dense counterpart of {@code parentEntryVisible}; by disjointness no
   * bucket check is needed. A nearer level with no trace-level ids cannot shadow a trace-level one.
   */
  private boolean parentDenseVisible(long denseId, TagMap fromAncestor) {
    boolean traceLevel = KnownTagCodec.isTraceLevel(denseId);
    String tag = null; // resolved lazily, only if a nearer level carries tombstones
    for (DenseTagMap nearer = this; nearer != fromAncestor; nearer = (DenseTagMap) nearer.parent) {
      if ((!traceLevel || nearer.knownTraceLevel) && nearer.knownIndexOf(denseId) >= 0) {
        return false; // shadowed by a nearer dense value
      }
      if (nearer.removedFromParent != null) {
        if (tag == null) tag = KnownTagCodec.nameOf(denseId);
        if (nearer.removedFromParent.contains(tag)) return false; // tombstoned by a nearer level
      }
    }
    return true;
  }
}

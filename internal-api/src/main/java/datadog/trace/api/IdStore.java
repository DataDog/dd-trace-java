package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Known tags keyed by their {@link KnownTags} id. Every method takes an id, or an {@link
 * EntryReader} for a known tag -- never a name, so nothing here can be handed a name that a caller
 * has not yet resolved.
 *
 * <p>A span in the tracer core owns its id store directly ({@link #create}); a {@link TagMap} owns
 * one too.
 *
 * <p>{@link IdStoreFactory} loads one implementation per JVM, {@link HashIdStore} or {@link
 * DenseIdStore}. With one subclass loaded, class hierarchy analysis devirtualizes every call on an
 * {@code IdStore} without a type guard, so nothing that is always loaded may extend this class. No
 * locking: guarded by the owner's lock.
 *
 * <p>The base keeps the slot masks an implementation shares: a tag's slot ({@link
 * KnownTagCodec#slotOf}) maps it to one bit of a {@code long}. A bit is a may-be-present hint --
 * tags that don't usually co-occur share a slot -- so a clear bit is a definite miss and a set bit
 * means "probe". The masks are never cleared by a removal, only widened.
 *
 * <p>Span-level and trace-level tags are colored separately and reuse the same slots, since they
 * are meant to live in separate stores (a span's trace-level tags in the frozen parent it reads
 * through). So each store also records which levels it holds, local and inherited, and a lookup
 * consults the slot bits only for a level that is present: in a store holding one level the word is
 * exact, and a store holding both still works, with a cross-level collision costing a probe.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public abstract class IdStore {
  /** Visits a store's visible entries one at a time. */
  interface Cursor {
    /** The next visible entry, or null when there are none left. */
    EntryReader next();
  }

  /** Slots that may hold a live local entry. */
  long occupancy;

  /** Slots that may hold a local tombstone, hiding an inherited entry. */
  long tombstoneMask;

  /**
   * Slots an ancestor may hold, fixed at construction: ancestors are frozen. A clear bit lets a
   * local miss skip read-through.
   */
  final long inheritedMask;

  /** The levels ({@link #SPAN_LEVEL}, {@link #TRACE_LEVEL}) of the local entries and tombstones. */
  byte levels;

  /** The levels an ancestor may hold, fixed at construction like {@link #inheritedMask}. */
  final byte inheritedLevels;

  static final int SPAN_LEVEL = 1;
  static final int TRACE_LEVEL = 2;

  IdStore(IdStore parent) {
    if (parent == null) {
      this.inheritedMask = 0L;
      this.inheritedLevels = 0;
    } else {
      this.inheritedMask = parent.occupancy | parent.inheritedMask;
      this.inheritedLevels = (byte) (parent.levels | parent.inheritedLevels);
    }
  }

  /** A fresh id store reading through to {@code parent}'s known tags (parent may be null). */
  public static IdStore create(TagMap parent) {
    return IdStoreFactory.Default.INSTANCE.create(parent == null ? null : parent.ids);
  }

  /** {@code tagId}'s slot bit. */
  static long slotBit(long tagId) {
    return 1L << KnownTagCodec.slotOf(tagId);
  }

  /** {@code tagId}'s level: {@link #SPAN_LEVEL} or {@link #TRACE_LEVEL}, without a branch. */
  static int levelOf(long tagId) {
    return 1 << (int) ((tagId & KnownTagCodec.LEVEL_TRACE) >>> 2);
  }

  /** Whether the slot {@code bit} may hold a local entry or tombstone of {@code level}. */
  final boolean mayHaveLocal(long bit, int level) {
    return (levels & level) != 0 && ((occupancy | tombstoneMask) & bit) != 0L;
  }

  /** Whether an ancestor may hold a tag of {@code level} in the slot {@code bit}. */
  final boolean mayInherit(long bit, int level) {
    return (inheritedLevels & level) != 0 && (inheritedMask & bit) != 0L;
  }

  /** Records a live local entry in the slot {@code bit} of {@code level}. */
  final void markLocal(long bit, int level) {
    this.occupancy |= bit;
    this.levels |= (byte) level;
  }

  /** Records a local tombstone in the slot {@code bit} of {@code level}. */
  final void markTombstone(long bit, int level) {
    this.tombstoneMask |= bit;
    this.levels |= (byte) level;
  }

  /** Throws for an id that names no known tag. */
  static IllegalArgumentException notKnown(long tagId) {
    return new IllegalArgumentException("not a known tag id: " + Long.toHexString(tagId));
  }

  /*
   * Point operations. A returned EntryReader is valid only until this store's next operation: a
   * store that keeps known tags without an Entry per tag answers with a reused flyweight. Call
   * EntryReader.entry() to keep one. Stores are inside the tracer's trust boundary, so callers are
   * expected to follow this rather than have it enforced.
   */

  /**
   * The entry for the known tag {@code tagId}, reading through to the parent chain, or null.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public abstract EntryReader getEntryReader(long tagId);

  /**
   * {@link #getEntryReader}'s value, boxed if necessary, or null -- for a caller that needs only
   * the value, so a store that keeps known tags without an Entry per tag can answer without one.
   */
  public abstract Object getValue(long tagId);

  /*
   * Sets a known tag without validating its id: the caller has checked KnownTagCodec.isKnown.
   */

  public abstract void set(long tagId, Object value);

  public abstract void set(long tagId, CharSequence value);

  public abstract void set(long tagId, boolean value);

  public abstract void set(long tagId, int value);

  public abstract void set(long tagId, long value);

  public abstract void set(long tagId, float value);

  public abstract void set(long tagId, double value);

  /**
   * Inserts or replaces a known tag, returning the local entry it replaced (or null). Does not
   * consult the read-through parent. A store that keeps Entry objects keeps {@code entryReader}'s
   * Entry (free when it is one); a store that doesn't copies its value.
   */
  public abstract EntryReader put(EntryReader entryReader);

  /** {@link #put}, returning the prior visible entry -- local, else read through. */
  public abstract EntryReader getAndPut(EntryReader entryReader);

  /**
   * Removes the known tag {@code tagId}, returning the prior visible entry; an inherited one is
   * hidden by a tombstone.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public abstract EntryReader remove(long tagId);

  /*
   * Whole-store operations, for the owning TagMap.
   */

  /** Exact count, including the visible part of the read-through chain. */
  abstract int size();

  abstract boolean isEmpty();

  /** Cheap: no level of the chain holds a local entry (ignores shadowing and tombstones). */
  abstract boolean isDefinitelyEmpty();

  /** Upper bound: the local counts of every level. */
  abstract int estimateSize();

  /** Whether this store reads through to a parent. */
  abstract boolean hasParent();

  /** Visits the local entries, then the visible inherited ones. */
  abstract void forEach(Consumer<? super EntryReader> consumer);

  abstract <T> void forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer);

  abstract <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer);

  /**
   * A cursor over the visible entries, local then inherited, for an iterator. Each entry it returns
   * is valid until its next call, as with a point read.
   */
  abstract Cursor cursor();

  /** An independently mutable copy with the same parent, sharing any Entry objects it holds. */
  abstract IdStore copy();

  /** Copies the entries of the parentless store {@code that}, sharing any Entry objects. */
  abstract void putAllLocal(IdStore that);

  /** Puts the local entries into {@code map}, for a store with no parent. */
  abstract void fillMapLocal(Map<? super String, Object> map);

  abstract void fillStringMapLocal(Map<? super String, ? super String> map);

  /** Tests only: throws if the store's structure is inconsistent. */
  abstract void checkIntegrity();

  /** Tests only: the local count, recomputed from the structure. */
  abstract int computeSize();

  /** For deep debugging. */
  abstract String toInternalString();
}

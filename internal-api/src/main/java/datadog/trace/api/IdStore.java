package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.Entry;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Known tags keyed by their {@link KnownTags} id. Every method takes an id, or an Entry already
 * built from one -- never a name, so nothing here can be handed a name that a caller has not yet
 * resolved.
 *
 * <p>A span in the tracer core owns its id store directly ({@link TagRouting#newIdStore}); a {@link
 * TagMap} owns one too.
 *
 * <p>Each implementation is its own class: {@link HashIdStore} today, a dense store later. Nothing
 * that is always loaded may extend this class, so that a call through it stays monomorphic. No
 * locking: guarded by the owner's lock.
 *
 * <p>The base keeps the slot masks an implementation shares: a tag's slot ({@link
 * KnownTagCodec#slotOf}) maps it to one bit of a {@code long}. A bit is a may-be-present hint --
 * tags that don't usually co-occur share a slot -- so a clear bit is a definite miss and a set bit
 * means "probe". The masks are never cleared by a removal, only widened.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public abstract class IdStore {
  /** Slots that may hold a live local entry. */
  long occupancy;

  /** Slots that may hold a local tombstone, hiding an inherited entry. */
  long tombstoneMask;

  /**
   * Slots an ancestor may hold, fixed at construction: ancestors are frozen. A clear bit lets a
   * local miss skip read-through.
   */
  final long inheritedMask;

  IdStore(IdStore parent) {
    this.inheritedMask = parent == null ? 0L : parent.occupancy | parent.inheritedMask;
  }

  /** {@code tagId}'s slot bit. */
  static long slotBit(long tagId) {
    return 1L << KnownTagCodec.slotOf(tagId);
  }

  /** Whether the slot {@code bit} may hold a local entry or tombstone. */
  final boolean mayHaveLocal(long bit) {
    return ((occupancy | tombstoneMask) & bit) != 0L;
  }

  /** Whether an ancestor may hold a tag in the slot {@code bit}. */
  final boolean mayInherit(long bit) {
    return (inheritedMask & bit) != 0L;
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
  public abstract EntryReader get(long tagId);

  /**
   * {@code get(tagId)}'s value, boxed if necessary, or null -- for a caller that needs only the
   * value, so a store that keeps known tags without an Entry per tag can answer without one.
   */
  public abstract Object getKnownValue(long tagId);

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
   * Inserts or replaces a known tag's entry, returning the local entry it replaced (or null). Does
   * not consult the read-through parent.
   */
  public abstract EntryReader put(Entry knownEntry);

  /** {@link #put(Entry)}, returning the prior visible entry -- local, else read through. */
  public abstract EntryReader getAndPut(Entry knownEntry);

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

  /** An independently mutable copy with the same parent, sharing Entry objects. */
  abstract IdStore copy();

  /** Copies the entries of the parentless store {@code that}, sharing Entry objects. */
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

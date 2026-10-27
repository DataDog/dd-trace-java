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
 * <p>A span in the tracer core owns its id store directly ({@link IdStoreFactory#create}); a {@link
 * TagMap} owns one too.
 *
 * <p>Values move by {@code load} and {@code store}, as through {@link TagStore}; entries by {@code
 * getEntry}, {@code put} and {@code remove}.
 *
 * <p>{@link IdStoreFactory} builds every store, so the JVM loads one implementation and class
 * hierarchy analysis devirtualizes every call on an {@code IdStore} without a type guard; nothing
 * that is always loaded may extend this class. No locking: guarded by the owner's lock.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public abstract class IdStore {
  /** Visits a store's visible entries one at a time. */
  interface Cursor {
    /** The next visible entry, or null when there are none left. */
    EntryReader next();
  }

  /** A fresh id store reading through to {@code parent}'s known tags (parent may be null). */
  public static IdStore create(TagMap parent) {
    return IdStoreFactory.Default.INSTANCE.create(parent == null ? null : parent.knownStore);
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
   * The entry for the known tag {@code tagId}, reading through to the parent chain, or null. Unlike
   * {@link #getEntryReader}, the result is an {@link TagMap.Entry} the caller may keep, and it
   * shares no state with the store, so it is safe to call concurrently on a frozen store that
   * several threads read, such as a span prototype's or the tracer-level tags.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public abstract TagMap.Entry getEntry(long tagId);

  /**
   * {@link #getEntryReader}'s value, boxed if necessary, or null -- for a caller that needs only
   * the value, so a store that keeps known tags without an Entry per tag can answer without one.
   */
  public abstract Object load(long tagId);

  /**
   * {@link #load} as a String, or null. A store that keeps an Entry per tag answers with the
   * Entry's cached string, so a numeric tag read repeatedly, as the serializer does, is formatted
   * once.
   */
  public abstract String loadString(long tagId);

  /*
   * Stores a known tag without validating its id: the caller has checked KnownTagCodec.isKnown.
   */

  public abstract void store(long tagId, Object value);

  public abstract void store(long tagId, CharSequence value);

  public abstract void store(long tagId, boolean value);

  public abstract void store(long tagId, int value);

  public abstract void store(long tagId, long value);

  public abstract void store(long tagId, float value);

  public abstract void store(long tagId, double value);

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

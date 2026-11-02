package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.internal.VisibleForTesting;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Known tags keyed by their {@link KnownTags} id. Every method takes an id, or an {@link
 * EntryReader} for a known tag -- never a name, so nothing here can be handed a name that a caller
 * has not yet resolved.
 *
 * <p>A span in the tracer core owns its id store directly ({@link IdStoreFactory#create}); a {@link
 * TagMap} owns one too.
 *
 * <p>Values move by {@code load} and {@code store}, as through {@link TagStore}; entries by {@code
 * getEntry}, {@code put} and {@code remove}. {@link TagStore} treats a null value as a removal; an
 * id store takes no nulls at all, so its owner turns a null value into {@code remove} and skips a
 * null entry before calling.
 *
 * <p>Build stores only through {@link IdStoreFactory}, which says why. No locking: guarded by the
 * owner's lock.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public abstract class IdStore {
  /** Visits a store's visible entries one at a time. */
  interface Cursor {
    /** The next visible entry, or null when there are none left. */
    @Nullable
    EntryReader next();
  }

  /** Throws for an id that names no known tag. */
  static IllegalArgumentException notKnown(long tagId) {
    return new IllegalArgumentException("not a known tag id: " + Long.toHexString(tagId));
  }

  /**
   * The entry for the known tag {@code tagId}, reading through to the parent chain, or null.
   *
   * <p>The reader is valid only until this store's next operation: a store that keeps known tags
   * without an Entry per tag answers with a reused flyweight. Call {@link EntryReader#entry()} to
   * keep it, or use {@link #getEntry} instead. Stores are inside the tracer's trust boundary, so
   * callers are expected to follow this rather than have it enforced.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  @Nullable
  public abstract EntryReader getEntryReader(long tagId);

  /**
   * The entry for the known tag {@code tagId}, reading through to the parent chain, or null. Unlike
   * {@link #getEntryReader}, the result is an {@link TagMap.Entry} the caller may keep, and it
   * shares no state with the store, so it is safe to call concurrently on a frozen store that
   * several threads read, such as a span prototype's or the tracer-level tags.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  @Nullable
  public abstract TagMap.Entry getEntry(long tagId);

  /**
   * {@link #getEntryReader}'s value, boxed if necessary, or null -- for a caller that needs only
   * the value, so a store that keeps known tags without an Entry per tag can answer without one.
   */
  @Nullable
  public abstract Object load(long tagId);

  /**
   * {@link #load} as a String, or null. A store that keeps an Entry per tag answers with the
   * Entry's cached string, so a numeric tag read repeatedly, as the serializer does, is formatted
   * once.
   */
  @Nullable
  public abstract String loadString(long tagId);

  /**
   * Inserts or replaces the known tag {@code tagId} with {@code value}, without reading through to
   * the parent chain; a tombstone for the tag is replaced, so the new value is visible.
   *
   * <p>Does not validate {@code tagId}: the caller has checked {@link KnownTagCodec#isKnown}. Takes
   * no null: the owner calls {@link #remove} for a null value.
   */
  public abstract void store(long tagId, @Nonnull Object value);

  /** {@link #store(long, Object)} for a {@link CharSequence}. */
  public abstract void store(long tagId, @Nonnull CharSequence value);

  /** {@link #store(long, Object)} for a {@code boolean}, kept unboxed. */
  public abstract void store(long tagId, boolean value);

  /** {@link #store(long, Object)} for an {@code int}, kept unboxed. */
  public abstract void store(long tagId, int value);

  /** {@link #store(long, Object)} for a {@code long}, kept unboxed. */
  public abstract void store(long tagId, long value);

  /** {@link #store(long, Object)} for a {@code float}, kept unboxed. */
  public abstract void store(long tagId, float value);

  /** {@link #store(long, Object)} for a {@code double}, kept unboxed. */
  public abstract void store(long tagId, double value);

  /**
   * Inserts or replaces a known tag, returning the local entry it replaced (or null). Does not
   * consult the read-through parent. A store that keeps Entry objects keeps {@code entryReader}'s
   * Entry (free when it is one); a store that doesn't copies its value.
   */
  @Nullable
  public abstract EntryReader put(@Nonnull EntryReader entryReader);

  /**
   * {@link #put}, returning the prior visible entry -- local, else read through -- or null if the
   * tag was not visible (absent from the chain, or removed by a tombstone).
   */
  @Nullable
  public abstract EntryReader getAndPut(@Nonnull EntryReader entryReader);

  /**
   * Removes the known tag {@code tagId}, returning the prior visible entry; an inherited one is
   * hidden by a tombstone.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  @Nullable
  public abstract EntryReader remove(long tagId);

  /*
   * Whole-store operations, for the owning TagMap.
   */

  /** Exact count, including the visible part of the read-through chain. */
  abstract int size();

  /**
   * Exact: no tag is visible. With tombstones in a read-through chain this may walk the ancestors,
   * so a fast path should try {@link #isDefinitelyEmpty} first.
   */
  abstract boolean isEmpty();

  /**
   * Cheap: no level of the chain holds a local entry (ignores shadowing and tombstones). True means
   * empty; false means only "maybe not", since tombstones can hide every inherited tag. Use it to
   * skip work on a fast path, and {@link #isEmpty} when the answer must be exact.
   */
  abstract boolean isDefinitelyEmpty();

  /** Upper bound: the local counts of every level. */
  abstract int estimateSize();

  /** Whether this store reads through to a parent. */
  abstract boolean hasParent();

  /** Visits the local entries, then the visible inherited ones. */
  abstract void forEach(Consumer<? super EntryReader> consumer);

  /**
   * {@link #forEach(Consumer)}, passing {@code thisObj} as the consumer's first argument, so the
   * consumer can be a non-capturing lambda that the JVM keeps as a constant instead of allocating
   * per call: {@code store.forEach(map, (m, entry) -> m.put(entry.tag(), entry.objectValue()))}.
   */
  abstract <T> void forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer);

  /**
   * {@link #forEach(Object, BiConsumer)} with a second context object, passed as the consumer's
   * second argument: {@code store.forEach(this, sink, (self, s, entry) -> self.write(s, entry))}.
   */
  abstract <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer);

  /**
   * A cursor over the visible entries, local then inherited, for an iterator. Each entry it returns
   * is valid until its next call, as with a point read.
   */
  abstract Cursor cursor();

  /** An independently mutable copy with the same parent, sharing any Entry objects it holds. */
  abstract IdStore copy();

  /**
   * Copies the entries of the parentless store {@code that} (asserted), sharing any Entry objects.
   * "Local" names the source: only {@code that}'s own entries are copied, since it has no parent.
   */
  abstract void putAllLocal(IdStore that);

  /** Puts the local entries into {@code map}, for a store with no parent (asserted). */
  abstract void fillMapLocal(Map<? super String, Object> map);

  /** {@link #fillMapLocal} with each value as a String. */
  abstract void fillStringMapLocal(Map<? super String, ? super String> map);

  /** Tests only: throws if the store's structure is inconsistent. */
  @VisibleForTesting
  abstract void checkIntegrity();

  /** Tests only: the local count, recomputed from the structure. */
  @VisibleForTesting
  abstract int computeSize();

  /** For deep debugging. */
  abstract String toInternalString();
}

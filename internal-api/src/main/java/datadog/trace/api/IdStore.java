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
 * getEntry}, {@code put} and {@code getAndRemove}. {@link TagStore} treats a null value as a
 * removal; an id store takes no nulls at all, so its owner turns a null value into {@code remove}
 * and skips a null entry before calling.
 *
 * <p>Build stores only through {@link IdStoreFactory}, which says why: a JVM loads one
 * implementation, {@link HashIdStore} or {@link DenseIdStore}. No locking: guarded by the owner's
 * lock.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public abstract class IdStore {
  /** Visits a store's visible entries one at a time. */
  interface Cursor {
    /**
     * Advances to the next visible entry.
     *
     * @return the next visible entry, or null when there are none left
     */
    @Nullable
    EntryReader next();
  }

  /**
   * Creates an id store for a map.
   *
   * @param parent the frozen map whose known tags the new store reads through to; may be null
   * @return a fresh, empty id store
   */
  public static IdStore create(TagMap parent) {
    return create(parent, null);
  }

  /**
   * Like {@link #create(TagMap)}, presized from {@code hint} (see {@link SizingHintTable}); a null
   * hint, or a store that isn't sized per span, ignores it.
   */
  public static IdStore create(TagMap parent, @Nullable SizingHint hint) {
    return IdStoreFactory.Default.INSTANCE.create(parent == null ? null : parent.knownStore, hint);
  }

  /**
   * Feeds this store's size back into the hint it was presized from -- a best-effort, racy max, so
   * later stores of the same operation are presized to it. A store created without a hint, or with
   * a capped one, or that isn't sized per span, does nothing.
   */
  public void recordSize() {}

  /**
   * Copies every tag of {@code source} in at once if none of them can already be present here --
   * locally, as a tombstone, or inherited -- and returns true; otherwise copies nothing and returns
   * false, and the caller seeds tag by tag. A store that can't prove absence in bulk always returns
   * false. {@code source} must be frozen.
   */
  public boolean seedAbsent(IdStore source) {
    return false;
  }

  /**
   * The partial form of {@link #seedAbsent(IdStore)}: copies in the tags of {@code source} that
   * can't already be present here, hands each of the rest to {@code maybePresent} for the caller to
   * set tag by tag, and returns true. Returns false, having done nothing, when this store can't
   * seed in bulk at all or {@code source} isn't a plain frozen list of tags; the caller then sets
   * every tag itself. {@code source} must be frozen, and {@code maybePresent} must not keep the
   * entry it is handed.
   */
  public <T> boolean seedAbsent(
      IdStore source, T thisObj, BiConsumer<T, ? super EntryReader> maybePresent) {
    return false;
  }

  /**
   * Whether {@link #seedAbsent} can ever succeed for this kind of store, so a caller can skip it,
   * and the lock it needs, when it can't.
   */
  public boolean supportsBulkSeed() {
    return false;
  }

  /**
   * Builds the exception for an id that names no known tag.
   *
   * @param tagId the rejected id
   * @return the exception to throw
   */
  static IllegalArgumentException notKnown(long tagId) {
    return new IllegalArgumentException("not a known tag id: " + Long.toHexString(tagId));
  }

  /**
   * Looks up a known tag, reading through to the parent chain.
   *
   * <p>The reader is valid only until this store's next operation: a store that keeps known tags
   * without an Entry per tag answers with a reused flyweight. Call {@link EntryReader#entry()} to
   * keep it, or use {@link #getEntry} instead. Stores are inside the tracer's trust boundary, so
   * callers are expected to follow this rather than have it enforced.
   *
   * @param tagId the known tag's id
   * @return the visible entry, or null if the tag is absent or removed by a tombstone
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  @Nullable
  public abstract EntryReader getEntryReader(long tagId);

  /**
   * Looks up a known tag, reading through to the parent chain. Unlike {@link #getEntryReader}, the
   * result shares no state with the store, so this is safe to call concurrently on a frozen store
   * that several threads read, such as a span prototype's or the tracer-level tags.
   *
   * @param tagId the known tag's id
   * @return the visible tag as an {@link TagMap.Entry} the caller may keep, or null if the tag is
   *     absent or removed by a tombstone
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  @Nullable
  public abstract TagMap.Entry getEntry(long tagId);

  /**
   * Reads a known tag's value, for a caller that needs only the value, so a store that keeps known
   * tags without an Entry per tag can answer without one.
   *
   * @param tagId the known tag's id
   * @return {@link #getEntryReader}'s value, boxed if necessary, or null if the tag isn't visible
   */
  @Nullable
  public abstract Object load(long tagId);

  /**
   * Reads a known tag's value as a String. A store that keeps an Entry per tag answers with the
   * Entry's cached string, so a numeric tag read repeatedly, as the serializer does, is formatted
   * once.
   *
   * @param tagId the known tag's id
   * @return {@link #load}'s value as a String, or null if the tag isn't visible
   */
  @Nullable
  public abstract String loadString(long tagId);

  /**
   * Inserts or replaces a known tag, without reading through to the parent chain; a tombstone for
   * the tag is replaced, so the new value is visible.
   *
   * @param tagId the known tag's id; not validated, since the caller has checked {@link
   *     KnownTagCodec#isKnown}
   * @param value the value; never null, since the owner calls {@link #remove} for a null value
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
   * Inserts or replaces a known tag. Does not consult the read-through parent. A store that keeps
   * Entry objects keeps {@code entryReader}'s Entry (free when it is one); a store that doesn't
   * copies its value.
   *
   * <p>Returns nothing, like {@link #remove}: a caller that needs what it replaced uses {@link
   * #getAndPut}.
   *
   * @param entryReader the known tag to put
   */
  public abstract void put(@Nonnull EntryReader entryReader);

  /**
   * {@link #put}, also reporting the value the put hid.
   *
   * @param entryReader the known tag to put
   * @return the prior visible entry -- local, else read through -- or null if the tag was not
   *     visible (absent from the chain, or removed by a tombstone)
   */
  @Nullable
  public abstract EntryReader getAndPut(@Nonnull EntryReader entryReader);

  /**
   * Removes a known tag; an inherited one is hidden by a tombstone. Builds nothing to return, so a
   * caller that doesn't need the prior value neither allocates nor keeps it reachable.
   *
   * @param tagId the known tag's id
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public abstract void remove(long tagId);

  /**
   * {@link #remove} that returns the prior visible entry.
   *
   * @param tagId the known tag's id
   * @return the prior visible entry, or null if the tag was not visible
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  @Nullable
  public abstract EntryReader getAndRemove(long tagId);

  /*
   * Whole-store operations, for the owning TagMap.
   */

  /**
   * Counts the visible tags.
   *
   * @return the exact count, including the visible part of the read-through chain
   */
  abstract int size();

  /**
   * Checks exactly whether any tag is visible. With tombstones in a read-through chain this may
   * walk the ancestors, so a fast path should try {@link #isDefinitelyEmpty} first.
   *
   * @return whether no tag is visible
   */
  abstract boolean isEmpty();

  /**
   * Cheaply checks for emptiness, ignoring shadowing and tombstones. Use it to skip work on a fast
   * path, and {@link #isEmpty} when the answer must be exact.
   *
   * @return true if no level of the chain holds a local entry, so the store is empty; false means
   *     only "maybe not", since tombstones can hide every inherited tag
   */
  abstract boolean isDefinitelyEmpty();

  /**
   * Bounds the visible count from above, without resolving shadowing or tombstones.
   *
   * @return the sum of the local counts of every level
   */
  abstract int estimateSize();

  /**
   * Reports whether this store reads through to a parent.
   *
   * @return whether this store has a parent
   */
  abstract boolean hasParent();

  /**
   * Visits the local entries, then the visible inherited ones.
   *
   * @param consumer receives each visible entry
   */
  public abstract void forEach(Consumer<? super EntryReader> consumer);

  /**
   * {@link #forEach(Consumer)} with a context object, so the consumer can be a non-capturing lambda
   * that the JVM keeps as a constant instead of allocating per call: {@code store.forEach(map, (m,
   * entry) -> m.put(entry.tag(), entry.objectValue()))}.
   *
   * @param thisObj passed as the consumer's first argument
   * @param consumer receives {@code thisObj} and each visible entry
   */
  public abstract <T> void forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer);

  /**
   * {@link #forEach(Object, BiConsumer)} with a second context object: {@code store.forEach(this,
   * sink, (self, s, entry) -> self.write(s, entry))}.
   *
   * @param thisObj passed as the consumer's first argument
   * @param otherObj passed as the consumer's second argument
   * @param consumer receives both context objects and each visible entry
   */
  public abstract <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer);

  /**
   * {@link #forEach(Object, BiConsumer)} over the local entries only, skipping the parent chain.
   *
   * @param thisObj passed as the consumer's first argument
   * @param consumer receives {@code thisObj} and each local, live entry
   */
  abstract <T> void forEachLocal(T thisObj, BiConsumer<T, ? super EntryReader> consumer);

  /**
   * Starts a walk over the visible entries, local then inherited, for an iterator.
   *
   * @return a cursor whose returned entries are each valid until its next call, as with a point
   *     read
   */
  abstract Cursor cursor();

  /**
   * Copies this store.
   *
   * @return an independently mutable copy with the same parent, sharing any Entry objects it holds
   */
  abstract IdStore copy();

  /**
   * Copies the entries of a parentless store, sharing any Entry objects. "Local" names the source:
   * only {@code that}'s own entries are copied, since it has no parent.
   *
   * @param that the store to copy from; must have no parent (asserted)
   */
  abstract void putAllLocal(IdStore that);

  /**
   * Puts the local entries into a map, for a store with no parent (asserted).
   *
   * @param map receives each entry's tag name and value
   */
  abstract void fillMapLocal(Map<? super String, Object> map);

  /**
   * {@link #fillMapLocal} with each value as a String.
   *
   * @param map receives each entry's tag name and string value
   */
  abstract void fillStringMapLocal(Map<? super String, ? super String> map);

  /**
   * Tests only: checks the store's structure.
   *
   * @throws IllegalStateException if the structure is inconsistent
   */
  @VisibleForTesting
  abstract void checkIntegrity();

  /**
   * Tests only: recounts the local entries from the structure.
   *
   * @return the local count
   */
  @VisibleForTesting
  abstract int computeSize();

  /**
   * Dumps the internal structure, for deep debugging.
   *
   * @return a description of the store's internal layout
   */
  abstract String toInternalString();
}

package datadog.trace.api;

import datadog.trace.api.TagMap.Entry;

/**
 * The known-tag half of a {@link TagMap}: tags keyed by their {@link KnownTags} id. Every method
 * takes an id or an Entry already built from one -- never a name, so nothing here can be handed a
 * name that a caller has not yet resolved.
 *
 * <p>Each implementation is its own class, chosen when the owning TagMap is built. In hash mode the
 * IdStore is the {@link HashStore} that also holds the custom tags, so the split costs no extra
 * object. No locking: guarded by the owner's lock.
 */
abstract class IdStore {
  /**
   * The entry for the known tag {@code tagId}, reading through to the parent chain, or null.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  abstract Entry get(long tagId);

  /**
   * {@code get(tagId)}'s value, boxed if necessary, or null -- for a caller that needs only the
   * value, so a store that keeps known tags without an Entry per tag can answer without one.
   */
  abstract Object getKnownValue(long tagId);

  /**
   * Inserts or replaces a known tag's entry, returning the local entry it replaced (or null). Does
   * not consult the read-through parent.
   */
  abstract Entry put(Entry knownEntry);

  /** {@link #put(Entry)}, returning the prior visible entry -- local, else read through. */
  abstract Entry getAndPut(Entry knownEntry);

  /**
   * Removes the known tag {@code tagId}, returning the prior visible entry; an inherited one is
   * hidden by a tombstone.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  abstract Entry remove(long tagId);
}

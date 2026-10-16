package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.Entry;

/**
 * Known tags keyed by their {@link KnownTags} id. Every method takes an id, or an Entry already
 * built from one -- never a name, so nothing here can be handed a name that a caller has not yet
 * resolved.
 *
 * <p>Each implementation is its own class: today the adapter {@link HashStore#idStore()} returns
 * over a hash store's known tags; a dense store later. In hash mode ({@link TagRouting#HASH_MODE})
 * callers reach the hash store directly instead. No locking: guarded by the owner's lock.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public abstract class IdStore {
  /**
   * The entry for the known tag {@code tagId}, reading through to the parent chain, or null.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public abstract Entry get(long tagId);

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
  public abstract Entry put(Entry knownEntry);

  /** {@link #put(Entry)}, returning the prior visible entry -- local, else read through. */
  public abstract Entry getAndPut(Entry knownEntry);

  /**
   * Removes the known tag {@code tagId}, returning the prior visible entry; an inherited one is
   * hidden by a tombstone.
   *
   * @throws IllegalArgumentException if {@code tagId} names no known tag
   */
  public abstract Entry remove(long tagId);
}

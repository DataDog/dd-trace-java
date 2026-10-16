package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.Entry;

/**
 * Where a tag name is resolved and a read is routed to the store that holds it: a known tag to an
 * {@link IdStore} by id, a custom tag to a {@link HashStore} by name. The one place a name becomes
 * an id, so the stores themselves never see a name that might be a known tag's. Shared by {@link
 * TagMap} and by the tracer core's spans, which own their stores directly.
 *
 * <p>A custom store may be null: its owner creates it on the first custom write ({@link
 * #newCustomStore}), and until then may borrow its parent's. Writes and removals of custom tags are
 * the owner's, which alone can create its store.
 *
 * <p>A name resolves once, by {@link #keyOf}, to a key: a known tag's id ({@code > 0}), 0 for a
 * custom tag, or a negative sentinel for a direction-scoped OpenTelemetry alias ({@code
 * server.address}), whose tag depends on the span's direction. An alias is the caller's to decide:
 * the key-taking methods treat any key that is not an id as custom, which is what a TagMap does
 * with an alias, having no direction.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public final class TagRouting {
  private TagRouting() {}

  /** {@code tag}'s key: its id when known, 0 when custom, negative for a direction-scoped alias. */
  public static long keyOf(String tag) {
    return KnownTagCodec.keyOrAliasOf(tag);
  }

  /** Whether {@code key} is a known tag's id, as opposed to a custom tag or an alias. */
  public static boolean isKnown(long key) {
    return key > 0;
  }

  /*
   * Stores for an owner reading through to a frozen parent TagMap.
   */

  /**
   * The parent an owner reads through to: {@code parent}, or null when it is null or empty, as
   * {@link TagMap#createFromParent} decides.
   *
   * @throws IllegalStateException if {@code parent} is not frozen
   */
  public static TagMap readThroughParent(TagMap parent) {
    if (parent == null) {
      return null;
    }
    if (!parent.isFrozen()) {
      throw new IllegalStateException("read-through parent must be frozen");
    }
    return parent.isEmpty() ? null : parent;
  }

  /** A fresh id store reading through to {@code parent}'s known tags (parent may be null). */
  public static IdStore newIdStore(TagMap parent) {
    return new HashIdStore(parent == null ? null : parent.ids);
  }

  /**
   * {@code parent}'s custom store, for an owner to borrow read-only until its first custom write,
   * or null.
   */
  public static HashStore inheritedCustomStore(TagMap parent) {
    return parent == null ? null : parent.names;
  }

  /**
   * A fresh custom store for an owner's first custom write, reading through to {@code borrowed},
   * the store it borrowed until now (may be null).
   */
  public static HashStore newCustomStore(HashStore borrowed) {
    return new HashStore(borrowed);
  }

  /*
   * Reads, by name already resolved to its key by keyOf. A key that is not an id is a custom tag.
   */

  /** The entry for {@code tag}, already resolved to {@code key}, reading through to parents. */
  public static Entry get(IdStore ids, HashStore names, long key, String tag) {
    return isKnown(key) ? entryOf(ids.get(key)) : names == null ? null : names.get(tag);
  }

  /*
   * By name, on a span of a direction. A direction-scoped alias reads the tag it denotes, and a tag
   * reads a pending alias stored as written in the custom store, which, being the newer write,
   * wins. (A span keeps its pending aliases apart, and copies them into its custom store before
   * handing its tags to a reader that uses this.)
   */

  /**
   * The entry {@code name}, resolved to {@code key}, reads on a span of {@code direction}. With
   * {@code mayHavePendingAliases} false, a tag with an alias skips probing for one, so it costs a
   * single lookup like any other tag.
   */
  public static Entry getEntry(
      IdStore ids,
      HashStore names,
      long key,
      String name,
      int direction,
      boolean mayHavePendingAliases) {
    if (isKnown(key)) {
      return getById(ids, names, key, direction, mayHavePendingAliases);
    }
    Entry asWritten = (key == 0 || mayHavePendingAliases) && names != null ? names.get(name) : null;
    if (key == 0 || asWritten != null) {
      return asWritten;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    return isKnown(tagId) ? entryOf(ids.get(tagId)) : null;
  }

  /** The entry the known tag {@code tagId} reads on a span of {@code direction}; see above. */
  public static Entry getById(
      IdStore ids, HashStore names, long tagId, int direction, boolean mayHavePendingAliases) {
    if (mayHavePendingAliases
        && names != null
        && KnownTagCodec.hasScopedAlias(tagId)
        && direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      Entry pending = alias == null ? null : names.get(alias);
      if (pending != null) {
        return pending;
      }
    }
    return entryOf(ids.get(tagId));
  }

  /**
   * The Entry an id store's {@code reader} denotes, for a caller that hands it out of the trust
   * boundary or keeps it: free for a hash store's reader, which is its Entry; a store reading
   * through a reused flyweight builds one here.
   */
  public static Entry entryOf(TagMap.EntryReader reader) {
    return reader == null ? null : reader.entry();
  }
}

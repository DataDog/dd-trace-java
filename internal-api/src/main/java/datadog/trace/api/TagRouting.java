package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.Entry;

/**
 * Where a tag name is resolved and an operation is routed to the store that holds it: a known tag
 * to the {@link IdStore} by its id, a custom tag to the {@link HashStore} by its name. The one
 * place a name becomes an id, so the stores themselves never see a name that might be a known
 * tag's. Shared by {@link TagMap} and by the tracer core's spans, which own their stores directly.
 *
 * <p>A name resolves once, by {@link #keyOf}, to a key: a known tag's id ({@code > 0}), 0 for a
 * custom tag, or a negative sentinel for a direction-scoped OpenTelemetry alias ({@code
 * server.address}), whose tag depends on the span's direction. An alias is the caller's to decide:
 * the key-taking methods treat any key that is not an id as custom, which is what a TagMap does
 * with an alias, having no direction. {@link #getEntry(IdStore, HashStore, long, String, int,
 * boolean)} reads an alias by a span's direction.
 *
 * <p>In {@link #HASH_MODE} known tags live in the HashStore too, and the id-keyed methods go to it
 * directly: the flag is a constant, so the IdStore argument folds away and may be null.
 */
@Restricted(allowedIn = {"datadog.trace.core"})
public final class TagRouting {
  /**
   * True while known tags are kept in the HashStore: id-keyed operations call it directly rather
   * than through an {@link IdStore}, so an owner needs no IdStore object. False routes them through
   * the IdStore -- the shape a dense store needs.
   */
  public static final boolean HASH_MODE = true;

  private TagRouting() {}

  /** The IdStore an owner of {@code hash} pairs with it: none in hash mode. */
  public static IdStore idStoreFor(HashStore hash) {
    return HASH_MODE ? null : hash.idStore();
  }

  /** {@code tag}'s key: its id when known, 0 when custom, negative for a direction-scoped alias. */
  public static long keyOf(String tag) {
    return KnownTagCodec.keyOrAliasOf(tag);
  }

  /** Whether {@code key} is a known tag's id, as opposed to a custom tag or an alias. */
  public static boolean isKnown(long key) {
    return key > 0;
  }

  /*
   * By id.
   */

  /** The known tag {@code tagId}'s entry; throws for an id that names no known tag. */
  public static Entry getById(IdStore ids, HashStore hash, long tagId) {
    return HASH_MODE ? hash.getById(tagId) : ids.get(tagId);
  }

  /** {@link #getById}'s value, or null. */
  public static Object getKnownValue(IdStore ids, HashStore hash, long tagId) {
    return HASH_MODE ? hash.getKnownValue(tagId) : ids.getKnownValue(tagId);
  }

  /** Removes the known tag {@code tagId}; throws for an id that names no known tag. */
  public static Entry removeById(IdStore ids, HashStore hash, long tagId) {
    return HASH_MODE ? hash.removeById(tagId) : ids.remove(tagId);
  }

  /*
   * Sets a known tag without validating its id: the caller has checked KnownTagCodec.isKnown.
   */

  public static void setKnown(IdStore ids, HashStore hash, long tagId, Object value) {
    if (HASH_MODE) {
      hash.setKnown(tagId, value);
    } else {
      ids.set(tagId, value);
    }
  }

  public static void setKnown(IdStore ids, HashStore hash, long tagId, CharSequence value) {
    if (HASH_MODE) {
      hash.setKnown(tagId, value);
    } else {
      ids.set(tagId, value);
    }
  }

  public static void setKnown(IdStore ids, HashStore hash, long tagId, boolean value) {
    if (HASH_MODE) {
      hash.setKnown(tagId, value);
    } else {
      ids.set(tagId, value);
    }
  }

  public static void setKnown(IdStore ids, HashStore hash, long tagId, int value) {
    if (HASH_MODE) {
      hash.setKnown(tagId, value);
    } else {
      ids.set(tagId, value);
    }
  }

  public static void setKnown(IdStore ids, HashStore hash, long tagId, long value) {
    if (HASH_MODE) {
      hash.setKnown(tagId, value);
    } else {
      ids.set(tagId, value);
    }
  }

  public static void setKnown(IdStore ids, HashStore hash, long tagId, float value) {
    if (HASH_MODE) {
      hash.setKnown(tagId, value);
    } else {
      ids.set(tagId, value);
    }
  }

  public static void setKnown(IdStore ids, HashStore hash, long tagId, double value) {
    if (HASH_MODE) {
      hash.setKnown(tagId, value);
    } else {
      ids.set(tagId, value);
    }
  }

  /*
   * By entry, which already carries its id or its name.
   */

  /** Puts {@code entry} into the store for its kind, returning the local entry it replaced. */
  public static Entry put(IdStore ids, HashStore hash, Entry entry) {
    return HASH_MODE || entry.tagId() == 0 ? hash.put(entry) : ids.put(entry);
  }

  /** {@link #put}, returning the prior visible entry. */
  public static Entry getAndPut(IdStore ids, HashStore hash, Entry entry) {
    return HASH_MODE || entry.tagId() == 0 ? hash.getAndPut(entry) : ids.getAndPut(entry);
  }

  /*
   * By name, already resolved to its key by keyOf. A key that is not an id is a custom tag.
   */

  /** The entry for {@code tag}, already resolved to {@code key}, reading through to parents. */
  public static Entry get(IdStore ids, HashStore hash, long key, String tag) {
    return isKnown(key) ? getById(ids, hash, key) : hash.get(tag);
  }

  /** Removes {@code tag}, already resolved to {@code key}, returning the prior visible entry. */
  public static Entry remove(IdStore ids, HashStore hash, long key, String tag) {
    return isKnown(key) ? removeById(ids, hash, key) : hash.remove(tag);
  }

  public static void set(IdStore ids, HashStore hash, long key, String tag, Object value) {
    if (isKnown(key)) {
      setKnown(ids, hash, key, value);
    } else {
      hash.set(tag, value);
    }
  }

  public static void set(IdStore ids, HashStore hash, long key, String tag, CharSequence value) {
    if (isKnown(key)) {
      setKnown(ids, hash, key, value);
    } else {
      hash.set(tag, value);
    }
  }

  public static void set(IdStore ids, HashStore hash, long key, String tag, boolean value) {
    if (isKnown(key)) {
      setKnown(ids, hash, key, value);
    } else {
      hash.set(tag, value);
    }
  }

  public static void set(IdStore ids, HashStore hash, long key, String tag, int value) {
    if (isKnown(key)) {
      setKnown(ids, hash, key, value);
    } else {
      hash.set(tag, value);
    }
  }

  public static void set(IdStore ids, HashStore hash, long key, String tag, long value) {
    if (isKnown(key)) {
      setKnown(ids, hash, key, value);
    } else {
      hash.set(tag, value);
    }
  }

  public static void set(IdStore ids, HashStore hash, long key, String tag, float value) {
    if (isKnown(key)) {
      setKnown(ids, hash, key, value);
    } else {
      hash.set(tag, value);
    }
  }

  public static void set(IdStore ids, HashStore hash, long key, String tag, double value) {
    if (isKnown(key)) {
      setKnown(ids, hash, key, value);
    } else {
      hash.set(tag, value);
    }
  }

  /**
   * Copies every entry visible in {@code source} into the stores, sharing the Entry objects; see
   * {@link TagMap#putAll(TagMap)}.
   */
  public static void putAll(IdStore ids, HashStore hash, TagMap source) {
    TagMap.viewOf(ids, hash, null).putAll(source);
  }

  /*
   * By name, on a span of a direction. A direction-scoped alias reads the tag it denotes, and a tag
   * reads a pending alias (one stored as written, before the span had a direction), which, being
   * the newer write, wins.
   */

  /**
   * The entry {@code name}, resolved to {@code key}, reads on a span of {@code direction}. With
   * {@code mayHavePendingAliases} false, a tag with an alias skips probing for one, so it costs a
   * single lookup like any other tag.
   */
  public static Entry getEntry(
      IdStore ids,
      HashStore hash,
      long key,
      String name,
      int direction,
      boolean mayHavePendingAliases) {
    if (isKnown(key)) {
      return getById(ids, hash, key, direction, mayHavePendingAliases);
    }
    Entry asWritten = key == 0 || mayHavePendingAliases ? hash.get(name) : null;
    if (key == 0 || asWritten != null) {
      return asWritten;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    return isKnown(tagId) ? getById(ids, hash, tagId) : null;
  }

  /** The entry the known tag {@code tagId} reads on a span of {@code direction}; see above. */
  public static Entry getById(
      IdStore ids, HashStore hash, long tagId, int direction, boolean mayHavePendingAliases) {
    if (mayHavePendingAliases
        && KnownTagCodec.hasScopedAlias(tagId)
        && direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      Entry pending = alias == null ? null : hash.get(alias);
      if (pending != null) {
        return pending;
      }
    }
    return getById(ids, hash, tagId);
  }
}

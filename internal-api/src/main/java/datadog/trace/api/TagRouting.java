package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap.Entry;

/**
 * Where a tag name is resolved and an operation is routed to the store of a {@link TagMap} that
 * holds it: a known tag to its {@link IdStore} by id, a custom tag to its {@link HashStore} by name
 * (created on the first custom write). The one place a name becomes an id, so the stores themselves
 * never see a name that might be a known tag's. Shared by TagMap and by the tracer core's spans,
 * which hold their tags in a TagMap and route to it directly, skipping the checks TagMap's public
 * methods make.
 *
 * <p>A name resolves once, by {@link #keyOf}, to a key: a known tag's id ({@code > 0}), 0 for a
 * custom tag, or a negative sentinel for a direction-scoped OpenTelemetry alias ({@code
 * server.address}), whose tag depends on the span's direction. An alias is the caller's to decide:
 * the key-taking methods treat any key that is not an id as custom, which is what a TagMap does
 * with an alias, having no direction. {@link #getEntry(TagMap, long, String, int, boolean)} reads
 * an alias by a span's direction.
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
   * By id.
   */

  /** The known tag {@code tagId}'s entry; throws for an id that names no known tag. */
  public static Entry getById(TagMap tags, long tagId) {
    return tags.ids.get(tagId);
  }

  /** {@link #getById}'s value, or null. */
  public static Object getKnownValue(TagMap tags, long tagId) {
    return tags.ids.getKnownValue(tagId);
  }

  /** Removes the known tag {@code tagId}; throws for an id that names no known tag. */
  public static Entry removeById(TagMap tags, long tagId) {
    return tags.ids.remove(tagId);
  }

  /*
   * Sets a known tag without validating its id: the caller has checked KnownTagCodec.isKnown.
   */

  public static void setKnown(TagMap tags, long tagId, Object value) {
    tags.ids.set(tagId, value);
  }

  public static void setKnown(TagMap tags, long tagId, CharSequence value) {
    tags.ids.set(tagId, value);
  }

  public static void setKnown(TagMap tags, long tagId, boolean value) {
    tags.ids.set(tagId, value);
  }

  public static void setKnown(TagMap tags, long tagId, int value) {
    tags.ids.set(tagId, value);
  }

  public static void setKnown(TagMap tags, long tagId, long value) {
    tags.ids.set(tagId, value);
  }

  public static void setKnown(TagMap tags, long tagId, float value) {
    tags.ids.set(tagId, value);
  }

  public static void setKnown(TagMap tags, long tagId, double value) {
    tags.ids.set(tagId, value);
  }

  /*
   * Custom tags, by name: never a known tag's name.
   */

  /** The custom tag {@code tag}'s entry, reading through to parents, or null. */
  public static Entry getCustom(TagMap tags, String tag) {
    HashStore names = tags.names;
    return names == null ? null : names.get(tag);
  }

  /** Removes the custom tag {@code tag}, returning the prior visible entry. */
  public static Entry removeCustom(TagMap tags, String tag) {
    return tags.removeCustom(tag);
  }

  /** Sets the custom tag {@code tag}. */
  public static void setCustom(TagMap tags, String tag, Object value) {
    tags.namesForWrite().set(tag, value);
  }

  /*
   * By entry, which already carries its id or its name.
   */

  /** Puts {@code entry} into the store for its kind, returning the local entry it replaced. */
  public static Entry put(TagMap tags, Entry entry) {
    return entry.tagId() != 0 ? tags.ids.put(entry) : tags.namesForWrite().put(entry);
  }

  /** {@link #put}, returning the prior visible entry. */
  public static Entry getAndPut(TagMap tags, Entry entry) {
    return entry.tagId() != 0 ? tags.ids.getAndPut(entry) : tags.namesForWrite().getAndPut(entry);
  }

  /*
   * By name, already resolved to its key by keyOf. A key that is not an id is a custom tag.
   */

  /** The entry for {@code tag}, already resolved to {@code key}, reading through to parents. */
  public static Entry get(TagMap tags, long key, String tag) {
    return isKnown(key) ? tags.ids.get(key) : getCustom(tags, tag);
  }

  /** Removes {@code tag}, already resolved to {@code key}, returning the prior visible entry. */
  public static Entry remove(TagMap tags, long key, String tag) {
    return isKnown(key) ? tags.ids.remove(key) : tags.removeCustom(tag);
  }

  public static void set(TagMap tags, long key, String tag, Object value) {
    if (isKnown(key)) {
      tags.ids.set(key, value);
    } else {
      tags.namesForWrite().set(tag, value);
    }
  }

  public static void set(TagMap tags, long key, String tag, CharSequence value) {
    if (isKnown(key)) {
      tags.ids.set(key, value);
    } else {
      tags.namesForWrite().set(tag, value);
    }
  }

  public static void set(TagMap tags, long key, String tag, boolean value) {
    if (isKnown(key)) {
      tags.ids.set(key, value);
    } else {
      tags.namesForWrite().set(tag, value);
    }
  }

  public static void set(TagMap tags, long key, String tag, int value) {
    if (isKnown(key)) {
      tags.ids.set(key, value);
    } else {
      tags.namesForWrite().set(tag, value);
    }
  }

  public static void set(TagMap tags, long key, String tag, long value) {
    if (isKnown(key)) {
      tags.ids.set(key, value);
    } else {
      tags.namesForWrite().set(tag, value);
    }
  }

  public static void set(TagMap tags, long key, String tag, float value) {
    if (isKnown(key)) {
      tags.ids.set(key, value);
    } else {
      tags.namesForWrite().set(tag, value);
    }
  }

  public static void set(TagMap tags, long key, String tag, double value) {
    if (isKnown(key)) {
      tags.ids.set(key, value);
    } else {
      tags.namesForWrite().set(tag, value);
    }
  }

  /*
   * By name, on a span of a direction. A direction-scoped alias reads the tag it denotes, and a tag
   * reads a pending alias stored as written in the custom store, which, being the newer write,
   * wins. (A span keeps its pending aliases apart, and copies them into the custom store before
   * handing its TagMap to a reader that uses this.)
   */

  /**
   * The entry {@code name}, resolved to {@code key}, reads on a span of {@code direction}. With
   * {@code mayHavePendingAliases} false, a tag with an alias skips probing for one, so it costs a
   * single lookup like any other tag.
   */
  public static Entry getEntry(
      TagMap tags, long key, String name, int direction, boolean mayHavePendingAliases) {
    if (isKnown(key)) {
      return getById(tags, key, direction, mayHavePendingAliases);
    }
    Entry asWritten = key == 0 || mayHavePendingAliases ? getCustom(tags, name) : null;
    if (key == 0 || asWritten != null) {
      return asWritten;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    return isKnown(tagId) ? tags.ids.get(tagId) : null;
  }

  /** The entry the known tag {@code tagId} reads on a span of {@code direction}; see above. */
  public static Entry getById(
      TagMap tags, long tagId, int direction, boolean mayHavePendingAliases) {
    if (mayHavePendingAliases
        && KnownTagCodec.hasScopedAlias(tagId)
        && direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      Entry pending = alias == null ? null : getCustom(tags, alias);
      if (pending != null) {
        return pending;
      }
    }
    return tags.ids.get(tagId);
  }
}

package datadog.trace.core;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_NONE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.TagMap;
import datadog.trace.api.TagRouting;
import java.util.Arrays;

/**
 * A span's reading of the few tag names that depend on its direction. The tag stores ({@link
 * HashStore}, {@link IdStore}) keep every name as written and know nothing of span kinds -- baggage
 * is a TagMap too -- so the direction is applied here, by the span layer, to the span's stores when
 * a tag is written, read, removed or emitted. The read rule itself is {@link TagRouting}'s, so a
 * span's {@link TagMap#viewOf view} reads the same way.
 *
 * <p>A tag is stored once, under its id, whatever the direction: {@code peer.port} is one tag that
 * is emitted as {@code client.port} inbound and {@code server.port} outbound. A direction-scoped
 * OpenTelemetry name -- an alias, such as {@code server.address}, which is {@code http.hostname}
 * inbound and {@code peer.hostname} outbound -- denotes its tag by the rule {@code (alias,
 * direction) -> tag}.
 *
 * <p>Between a tag and its alias, the last write wins, with no ordering state (a TagMap has no
 * insertion order):
 *
 * <ul>
 *   <li>Once the span has a direction, an alias write is stored as its tag, overwriting it.
 *   <li>Before then, an alias write is stored as written, pending. A write of a tag that has an
 *       alias removes every pending alias that could denote it. So a pending alias that is still
 *       present is, by construction, newer than its tag, and wins over it when read and when
 *       resolved for emission.
 * </ul>
 *
 * <p>A removal counts as a write. Before the span has a direction, removing a tag also removes the
 * pending aliases that could denote it, and removing an alias also removes every tag it could
 * denote, so a removed value can't resurface once the span learns its kind.
 *
 * <p>The accepted corner: before the span has a direction, a write of either possible tag of an
 * alias removes the pending alias, even if the span's eventual direction maps the alias to the
 * other tag. Writing {@code server.address}, then {@code peer.hostname}, then making the span a
 * server loses the {@code server.address} value, which would have been {@code http.hostname}.
 * Likewise a value written under an alias that turns out to have no tag in the span's direction
 * ({@code client.port} on what becomes a client span) is removed by an earlier-direction write of a
 * tag it could have denoted ({@code peer.port}).
 *
 * <p>A span keeps its pending aliases -- the aliases it stored as written -- in a {@link
 * PendingAliases} holder, not in its stores: null until the first such write, so a span that never
 * writes one pays nothing, and dropped once they are all resolved. The holder is copied into the
 * span's custom store when its tags are emitted, so an alias that never resolves is emitted under
 * its literal name.
 *
 * <p>One more: an alias written before the span has a direction is resolved when the kind arrives,
 * without passing through {@code TagInterceptor} again, so split-by-tags on its tag doesn't see it.
 * An alias written once the direction is known is intercepted as its tag.
 */
final class DirectionalTags {

  /**
   * The tag stores DirectionalTags applies a span's direction to: the span itself, which owns them
   * (or, in tests, a TagMap). Known tags by id; custom tags -- and aliases stored as written -- by
   * name.
   */
  interface TagStores {
    Object knownValue(long tagId);

    void setKnown(long tagId, Object value);

    void removeKnown(long tagId);

    Object customValue(String name);

    void setCustom(String name, Object value);

    void removeCustom(String name);
  }

  /**
   * A span's pending aliases: the direction-scoped aliases it stored as written, with their values,
   * in write order. Usually one or two. Guarded by the span's tag lock.
   */
  static final class PendingAliases {
    String[] names = new String[2];
    Object[] values = new Object[2];
    int size;

    /** The value pending under {@code alias}, or null. */
    Object get(String alias) {
      for (int i = 0; i < size; i++) {
        if (names[i].equals(alias)) {
          return values[i];
        }
      }
      return null;
    }

    void put(String alias, Object value) {
      for (int i = 0; i < size; i++) {
        if (names[i].equals(alias)) {
          values[i] = value;
          return;
        }
      }
      if (size == names.length) {
        names = Arrays.copyOf(names, size * 2);
        values = Arrays.copyOf(values, size * 2);
      }
      names[size] = alias;
      values[size] = value;
      size++;
    }

    void remove(String alias) {
      for (int i = 0; i < size; i++) {
        if (names[i].equals(alias)) {
          int moved = size - i - 1;
          System.arraycopy(names, i + 1, names, i, moved);
          System.arraycopy(values, i + 1, values, i, moved);
          size--;
          names[size] = null;
          values[size] = null;
          return;
        }
      }
    }

    boolean isEmpty() {
      return size == 0;
    }
  }

  /** {@code pending} with {@code alias} pending under {@code value}, created if null. */
  static PendingAliases addPending(PendingAliases pending, String alias, Object value) {
    if (pending == null) {
      pending = new PendingAliases();
    }
    pending.put(alias, value);
    return pending;
  }

  /**
   * Prepares a write of {@code alias} to a span of {@code direction}. Returns the id of the tag to
   * store the value under; {@link KnownTagCodec#NO_TAG_IN_DIRECTION_SENTINEL} when {@code alias} is
   * a direction-scoped alias that can't resolve yet, so its value is kept as written, pending; or 0
   * when it is no alias at all, so its value is an ordinary custom tag. A span keeps a pending
   * alias only for the sentinel, so a name that merely passed {@link
   * KnownTagCodec#mayBeScopedAlias}'s filter doesn't slow the span down.
   */
  static long prepareAliasWrite(
      TagStores tags, PendingAliases pending, String alias, int direction) {
    long tagId = KnownTagCodec.directionalKeyOf(alias, direction);
    if (tagId > 0) {
      // a value pending under the alias is older than this write
      removeAsWritten(tags, pending, alias);
    }
    return tagId;
  }

  /**
   * Prepares a write of the known tag {@code tagId} to a span of {@code direction}: when the tag
   * has an alias ({@link KnownTagCodec#HAS_SCOPED_ALIAS}), removes the pending aliases the write
   * supersedes. A caller with a constant id that lacks the bit can skip the call.
   */
  static void prepareWrite(TagStores tags, PendingAliases pending, long tagId, int direction) {
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(tags, pending, tagId, direction);
    }
  }

  /**
   * Removes the aliases a write of {@code tagId} supersedes: the one it has in {@code direction},
   * or, with no direction yet, any it could have.
   */
  private static void removePendingAliases(
      TagStores tags, PendingAliases pending, long tagId, int direction) {
    if (direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      if (alias != null) {
        removeAsWritten(tags, pending, alias);
      }
      return;
    }
    for (int d = DIRECTION_INBOUND; d <= DIRECTION_NONE; d++) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, d);
      if (alias != null) {
        removeAsWritten(tags, pending, alias);
      }
    }
  }

  /**
   * Removes {@code alias} as written: from the holder, and from the custom store, which holds it
   * once emitted, or when inherited or written through the span's view.
   */
  private static void removeAsWritten(TagStores tags, PendingAliases pending, String alias) {
    tags.removeCustom(alias);
    if (pending != null) {
      pending.remove(alias);
    }
  }

  /**
   * The value of {@code name} on a span of {@code direction}, whose pending aliases are in {@code
   * pending}. An alias reads its tag, and a tag reads its alias; a pending alias, being the newer
   * write, wins. With no holder, a tag with an alias skips probing for one, so it costs a single
   * lookup like any other tag.
   */
  static Object get(TagStores tags, PendingAliases pending, String name, int direction) {
    long key = TagRouting.keyOf(name);
    if (key > 0) {
      return get(tags, pending, key, direction);
    }
    if (key == 0) {
      return tags.customValue(name);
    }
    Object written = pending == null ? null : pending.get(name);
    if (written != null) {
      return written;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    return tagId > 0 ? tags.knownValue(tagId) : null;
  }

  /**
   * The value of the known tag {@code tagId} on a span of {@code direction}; see {@link
   * #get(TagStores, PendingAliases, String, int)}.
   */
  static Object get(TagStores tags, PendingAliases pending, long tagId, int direction) {
    if (pending != null && KnownTagCodec.hasScopedAlias(tagId) && direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      Object written = alias == null ? null : pending.get(alias);
      if (written != null) {
        return written;
      }
    }
    return tags.knownValue(tagId);
  }

  /** Removes {@code name} from a span of {@code direction}: the tag and its alias together. */
  static void remove(TagStores tags, PendingAliases pending, String name, int direction) {
    // One lookup decides everything: a known tag, a custom tag, or a direction-scoped alias.
    long key = KnownTagCodec.keyOrAliasOf(name);
    if (key > 0) {
      remove(tags, pending, key, direction);
      return;
    }
    if (key == 0) {
      tags.removeCustom(name); // a custom tag
      return;
    }
    removeAsWritten(tags, pending, name);
    if (direction == DIRECTION_UNKNOWN) {
      removeEveryTagOfAlias(tags, name);
      return;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    if (tagId > 0) {
      tags.removeKnown(tagId);
    }
  }

  /** Removes the known tag {@code tagId}, and its alias, from a span of {@code direction}. */
  static void remove(TagStores tags, PendingAliases pending, long tagId, int direction) {
    tags.removeKnown(tagId);
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(tags, pending, tagId, direction);
    }
  }

  /**
   * Removes every tag {@code alias} could denote, for a removal of the alias before the span has a
   * direction. A removal counts as a write, so no older value can resurface when the kind arrives;
   * like the write rule, this can drop the other direction's tag. (Removing a tag before then
   * likewise removes the pending aliases that could denote it; see {@link #remove(TagStores,
   * PendingAliases, long, int)}.)
   */
  private static void removeEveryTagOfAlias(TagStores tags, String alias) {
    for (int direction = DIRECTION_INBOUND; direction <= DIRECTION_NONE; direction++) {
      long aliasedId = KnownTagCodec.directionalKeyOf(alias, direction);
      if (aliasedId > 0) {
        tags.removeKnown(aliasedId);
      }
    }
  }

  /**
   * Resolves a span's pending aliases, once it has a direction, into the tags they denote: the
   * alias, being the newer write, overwrites its tag. Returns the holder of those left pending --
   * an alias with no tag in {@code direction}, or any while the direction is unknown or none -- or
   * null when none are.
   */
  static PendingAliases resolvePending(TagStores tags, PendingAliases pending, int direction) {
    if (direction == DIRECTION_UNKNOWN || direction == DIRECTION_NONE) {
      return pending; // no alias names a tag on a span with no direction, or an internal one
    }
    for (int i = 0; i < pending.size; ) {
      String alias = pending.names[i];
      long tagId = KnownTagCodec.directionalKeyOf(alias, direction);
      if (tagId > 0) {
        Object value = pending.values[i];
        tags.removeCustom(alias); // a copy emitted earlier
        pending.remove(alias);
        tags.setKnown(tagId, value);
      } else {
        i++;
      }
    }
    return pending.isEmpty() ? null : pending;
  }

  /**
   * Copies the pending aliases into {@code tags} as custom tags under their literal names, so that
   * emitting the span's tags includes them. The holder stays the span's record of them.
   */
  static void copyPending(PendingAliases pending, TagStores tags) {
    for (int i = 0; i < pending.size; i++) {
      tags.setCustom(pending.names[i], pending.values[i]);
    }
  }

  private DirectionalTags() {}
}

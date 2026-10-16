package datadog.trace.core;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_NONE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;

import datadog.trace.api.HashStore;
import datadog.trace.api.IdStore;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.TagMap;
import datadog.trace.api.TagRouting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 * span's HashStore when its tags are emitted, so an alias that never resolves is emitted under its
 * literal name. The store-only methods below, which take no holder, read and resolve aliases stored
 * as written in the HashStore itself, as a TagMap holds them.
 *
 * <p>One more: an alias written before the span has a direction is resolved when the kind arrives,
 * without passing through {@code TagInterceptor} again, so split-by-tags on its tag doesn't see it.
 * An alias written once the direction is known is intercepted as its tag.
 */
final class DirectionalTags {

  /**
   * A span's pending aliases: the direction-scoped aliases it stored as written, with their values,
   * in write order. Usually one or two. Guarded by the span's tag lock.
   */
  static final class PendingAliases {
    private String[] names = new String[2];
    private Object[] values = new Object[2];
    private int size;

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
   * a direction-scoped alias that can't resolve yet, so its value is stored as written, pending; or
   * 0 when it is no alias at all, so its value is an ordinary custom tag. Callers that keep pending
   * aliases keep one only for the sentinel, so a name that merely passed {@link
   * KnownTagCodec#mayBeScopedAlias}'s filter doesn't slow the span down.
   */
  static long prepareAliasWrite(IdStore ids, HashStore tags, String alias, int direction) {
    return prepareAliasWrite(ids, tags, null, alias, direction);
  }

  /** {@link #prepareAliasWrite(IdStore, HashStore, String, int)} for a span's stores. */
  static long prepareAliasWrite(
      IdStore ids, HashStore tags, PendingAliases pending, String alias, int direction) {
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
  static void prepareWrite(IdStore ids, HashStore tags, long tagId, int direction) {
    prepareWrite(ids, tags, null, tagId, direction);
  }

  /** {@link #prepareWrite(IdStore, HashStore, long, int)} for a span's stores. */
  static void prepareWrite(
      IdStore ids, HashStore tags, PendingAliases pending, long tagId, int direction) {
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(tags, pending, tagId, direction);
    }
  }

  /**
   * Removes the aliases a write of {@code tagId} supersedes: the one it has in {@code direction},
   * or, with no direction yet, any it could have.
   */
  private static void removePendingAliases(
      HashStore tags, PendingAliases pending, long tagId, int direction) {
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
   * Removes {@code alias} as written: from the holder, and from the HashStore, which holds it once
   * emitted, or when inherited or written through a view.
   */
  private static void removeAsWritten(HashStore tags, PendingAliases pending, String alias) {
    tags.remove(alias);
    if (pending != null) {
      pending.remove(alias);
    }
  }

  /**
   * The value {@code alias} holds as written: in the holder when there is one, else -- with {@code
   * inStore} -- in the HashStore.
   */
  private static Object asWritten(
      HashStore tags, PendingAliases pending, boolean inStore, String alias) {
    if (pending != null) {
      return pending.get(alias);
    }
    if (inStore) {
      TagMap.Entry entry = tags.get(alias);
      return entry == null ? null : entry.objectValue();
    }
    return null;
  }

  /**
   * The value of {@code name} on a span of {@code direction}, for stores that keep aliases as
   * written. An alias reads its tag, and a tag reads its alias; a pending alias, being the newer
   * write, wins. Every other name costs the same single lookup as {@link TagMap#getObject}.
   */
  static Object get(IdStore ids, HashStore tags, String name, int direction) {
    return get(ids, tags, null, true, name, direction);
  }

  /**
   * {@link #get(IdStore, HashStore, String, int)} for a span, whose pending aliases are in {@code
   * pending}. With no holder, a tag with an alias skips probing for one, so it costs a single
   * lookup like any other tag.
   */
  static Object get(
      IdStore ids, HashStore tags, PendingAliases pending, String name, int direction) {
    return get(ids, tags, pending, false, name, direction);
  }

  private static Object get(
      IdStore ids,
      HashStore tags,
      PendingAliases pending,
      boolean inStore,
      String name,
      int direction) {
    long key = TagRouting.keyOf(name);
    if (key > 0) {
      return get(ids, tags, pending, inStore, key, direction);
    }
    if (key == 0) {
      TagMap.Entry entry = tags.get(name);
      return entry == null ? null : entry.objectValue();
    }
    Object written = asWritten(tags, pending, inStore, name);
    if (written != null) {
      return written;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    return tagId > 0 ? TagRouting.getKnownValue(ids, tags, tagId) : null;
  }

  /**
   * The value of the known tag {@code tagId} on a span of {@code direction}; see {@link
   * #get(IdStore, HashStore, String, int)}.
   */
  static Object get(IdStore ids, HashStore tags, long tagId, int direction) {
    return get(ids, tags, null, true, tagId, direction);
  }

  /** {@link #get(IdStore, HashStore, long, int)} for a span; see above. */
  static Object get(
      IdStore ids, HashStore tags, PendingAliases pending, long tagId, int direction) {
    return get(ids, tags, pending, false, tagId, direction);
  }

  private static Object get(
      IdStore ids,
      HashStore tags,
      PendingAliases pending,
      boolean inStore,
      long tagId,
      int direction) {
    if ((pending != null || inStore)
        && KnownTagCodec.hasScopedAlias(tagId)
        && direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      Object written = alias == null ? null : asWritten(tags, pending, inStore, alias);
      if (written != null) {
        return written;
      }
    }
    return TagRouting.getKnownValue(ids, tags, tagId);
  }

  /** Removes {@code name} from a span of {@code direction}: the tag and its alias together. */
  static void remove(IdStore ids, HashStore tags, String name, int direction) {
    remove(ids, tags, null, name, direction);
  }

  /** {@link #remove(IdStore, HashStore, String, int)} for a span's stores. */
  static void remove(
      IdStore ids, HashStore tags, PendingAliases pending, String name, int direction) {
    // One lookup decides everything: a known tag, a custom tag, or a direction-scoped alias.
    long key = KnownTagCodec.keyOrAliasOf(name);
    if (key > 0) {
      remove(ids, tags, pending, key, direction);
      return;
    }
    if (key == 0) {
      tags.remove(name); // a custom tag
      return;
    }
    removeAsWritten(tags, pending, name);
    if (direction == DIRECTION_UNKNOWN) {
      removeEveryTagOfAlias(ids, tags, name);
      return;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    if (tagId > 0) {
      TagRouting.removeById(ids, tags, tagId);
    }
  }

  /** Removes the known tag {@code tagId}, and its alias, from a span of {@code direction}. */
  static void remove(IdStore ids, HashStore tags, long tagId, int direction) {
    remove(ids, tags, null, tagId, direction);
  }

  /** {@link #remove(IdStore, HashStore, long, int)} for a span's stores. */
  static void remove(
      IdStore ids, HashStore tags, PendingAliases pending, long tagId, int direction) {
    TagRouting.removeById(ids, tags, tagId);
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(tags, pending, tagId, direction);
    }
  }

  /**
   * Removes every tag {@code alias} could denote, for a removal of the alias before the span has a
   * direction. A removal counts as a write, so no older value can resurface when the kind arrives;
   * like the write rule, this can drop the other direction's tag. (Removing a tag before then
   * likewise removes the pending aliases that could denote it; see {@link #remove(IdStore,
   * HashStore, long, int)}.)
   */
  private static void removeEveryTagOfAlias(IdStore ids, HashStore tags, String alias) {
    for (int direction = DIRECTION_INBOUND; direction <= DIRECTION_NONE; direction++) {
      long aliasedId = KnownTagCodec.directionalKeyOf(alias, direction);
      if (aliasedId > 0) {
        TagRouting.removeById(ids, tags, aliasedId);
      }
    }
  }

  /**
   * Resolves each alias stored as written in {@code tags} to its tag, for a span of {@code
   * direction} about to be emitted. The alias, being the newer write, overwrites its tag. An alias
   * with no tag in {@code direction} -- or any, while the direction is unknown -- stays as written,
   * a custom tag.
   */
  static void resolveScopedNames(IdStore ids, HashStore tags, int direction) {
    if (direction == DIRECTION_UNKNOWN || direction == DIRECTION_NONE) {
      return; // no alias names a tag on a span with no direction, or an internal one
    }
    List<TagMap.Entry> pending = null;
    // Aliases stored as written are custom tags, so they are all in the HashStore.
    for (TagMap.EntryReader entry : TagMap.viewOf(ids, tags, null)) {
      if (entry.tagId() == 0 && KnownTagCodec.directionalKeyOf(entry.tag(), direction) > 0) {
        if (pending == null) {
          pending = new ArrayList<>(2);
        }
        pending.add(entry.entry());
      }
    }
    if (pending == null) {
      return;
    }
    for (TagMap.Entry entry : pending) {
      tags.remove(entry.tag());
      TagRouting.setKnown(
          ids, tags, KnownTagCodec.directionalKeyOf(entry.tag(), direction), entry.objectValue());
    }
  }

  /**
   * {@link #resolveScopedNames(IdStore, HashStore, int)} for a span: resolves its pending aliases,
   * once it has a direction, into the tags they denote, and returns the holder of those left
   * pending, or null when none are.
   */
  static PendingAliases resolvePending(
      IdStore ids, HashStore tags, PendingAliases pending, int direction) {
    if (direction == DIRECTION_UNKNOWN || direction == DIRECTION_NONE) {
      return pending; // no alias names a tag on a span with no direction, or an internal one
    }
    for (int i = 0; i < pending.size; ) {
      String alias = pending.names[i];
      long tagId = KnownTagCodec.directionalKeyOf(alias, direction);
      if (tagId > 0) {
        Object value = pending.values[i];
        tags.remove(alias); // a copy emitted earlier
        pending.remove(alias);
        TagRouting.setKnown(ids, tags, tagId, value);
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
  static void copyPending(PendingAliases pending, HashStore tags) {
    for (int i = 0; i < pending.size; i++) {
      tags.set(pending.names[i], pending.values[i]);
    }
  }

  private DirectionalTags() {}
}

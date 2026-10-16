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
 * <p>One more: an alias written before the span has a direction is resolved when the kind arrives,
 * without passing through {@code TagInterceptor} again, so split-by-tags on its tag doesn't see it.
 * An alias written once the direction is known is intercepted as its tag.
 */
final class DirectionalTags {

  /**
   * Prepares a write of {@code alias} to a span of {@code direction}. Returns the id of the tag to
   * store the value under; {@link KnownTagCodec#NO_TAG_IN_DIRECTION_SENTINEL} when {@code alias} is
   * a direction-scoped alias that can't resolve yet, so its value is stored as written, pending; or
   * 0 when it is no alias at all, so its value is an ordinary custom tag. Callers that keep a
   * pending-alias flag set it only for the sentinel, so a name that merely passed {@link
   * KnownTagCodec#mayBeScopedAlias}'s filter doesn't slow the span down.
   */
  static long prepareAliasWrite(IdStore ids, HashStore tags, String alias, int direction) {
    long tagId = KnownTagCodec.directionalKeyOf(alias, direction);
    if (tagId > 0) {
      tags.remove(alias); // a value pending under the alias is older than this write
    }
    return tagId;
  }

  /**
   * Prepares a write of the known tag {@code tagId} to a span of {@code direction}: when the tag
   * has an alias ({@link KnownTagCodec#HAS_SCOPED_ALIAS}), removes the pending aliases the write
   * supersedes. A caller with a constant id that lacks the bit can skip the call.
   */
  static void prepareWrite(IdStore ids, HashStore tags, long tagId, int direction) {
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(ids, tags, tagId, direction);
    }
  }

  /**
   * Removes the aliases a write of {@code tagId} supersedes: the one it has in {@code direction},
   * or, with no direction yet, any it could have.
   */
  private static void removePendingAliases(IdStore ids, HashStore tags, long tagId, int direction) {
    if (direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      if (alias != null) {
        tags.remove(alias);
      }
      return;
    }
    for (int d = DIRECTION_INBOUND; d <= DIRECTION_NONE; d++) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, d);
      if (alias != null) {
        tags.remove(alias);
      }
    }
  }

  /**
   * The value of {@code name} on a span of {@code direction}. An alias reads its tag, and a tag
   * reads its alias; a pending alias, being the newer write, wins. Every other name costs the same
   * single lookup as {@link TagMap#getObject}.
   */
  static Object get(IdStore ids, HashStore tags, String name, int direction) {
    return get(ids, tags, name, direction, true);
  }

  /**
   * {@link #get(IdStore, HashStore, String, int)} for a caller that tracks whether {@code tags} can
   * hold a pending alias at all. With {@code mayHavePendingAliases} false, a tag with an alias
   * skips probing for one, so it costs a single lookup like any other tag.
   */
  static Object get(
      IdStore ids, HashStore tags, String name, int direction, boolean mayHavePendingAliases) {
    TagMap.Entry entry =
        TagRouting.getEntry(
            ids, tags, TagRouting.keyOf(name), name, direction, mayHavePendingAliases);
    return entry == null ? null : entry.objectValue();
  }

  /**
   * The value of the known tag {@code tagId} on a span of {@code direction}; see {@link
   * #get(IdStore, HashStore, String, int)}.
   */
  static Object get(IdStore ids, HashStore tags, long tagId, int direction) {
    return get(ids, tags, tagId, direction, true);
  }

  /**
   * {@link #get(IdStore, HashStore, long, int)}; see {@link #get(IdStore, HashStore, String, int,
   * boolean)}.
   */
  static Object get(
      IdStore ids, HashStore tags, long tagId, int direction, boolean mayHavePendingAliases) {
    TagMap.Entry entry = TagRouting.getById(ids, tags, tagId, direction, mayHavePendingAliases);
    return entry == null ? null : entry.objectValue();
  }

  /** Removes {@code name} from a span of {@code direction}: the tag and its alias together. */
  static void remove(IdStore ids, HashStore tags, String name, int direction) {
    // One lookup decides everything: a known tag, a custom tag, or a direction-scoped alias.
    long key = KnownTagCodec.keyOrAliasOf(name);
    if (key > 0) {
      remove(ids, tags, key, direction);
      return;
    }
    tags.remove(name); // a custom tag, or an alias stored as written
    if (key == 0) {
      return;
    }
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
    TagRouting.removeById(ids, tags, tagId);
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(ids, tags, tagId, direction);
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
   * Resolves each pending alias to its tag, for a span of {@code direction} about to be emitted.
   * The alias, being the newer write, overwrites its tag. An alias with no tag in {@code direction}
   * -- or any, while the direction is unknown -- stays as written, a custom tag.
   */
  static void resolveScopedNames(IdStore ids, HashStore tags, int direction) {
    if (direction == DIRECTION_UNKNOWN || direction == DIRECTION_NONE) {
      return; // no alias names a tag on a span with no direction, or an internal one
    }
    List<TagMap.Entry> pending = null;
    // Pending aliases are custom tags, so they are all in the HashStore.
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

  private DirectionalTags() {}
}

package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_NONE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.ArrayList;
import java.util.List;

/**
 * A span's reading of the few tag names that depend on its direction. {@link TagMap} stores every
 * name as written and knows nothing of span kinds -- baggage is a TagMap too -- so the direction is
 * applied here, by the span layer, when a tag is written, read, removed or emitted.
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
 */
public final class SpanTags {
  /** The direction of a span with these tags, from its {@code span.kind}. */
  public static int directionOf(TagMap tags) {
    TagMap.Entry kind = tags.getEntry(KnownTags.SPAN_KIND_ID);
    return kind == null ? DIRECTION_UNKNOWN : directionOfKind(kind.stringValue());
  }

  /** The direction of a span of {@code kind}; brokers and custom kinds have none. */
  public static int directionOfKind(String kind) {
    if (kind == null) {
      return DIRECTION_UNKNOWN;
    }
    switch (kind) {
      case Tags.SPAN_KIND_SERVER:
      case Tags.SPAN_KIND_CONSUMER:
        return DIRECTION_INBOUND;
      case Tags.SPAN_KIND_CLIENT:
      case Tags.SPAN_KIND_PRODUCER:
        return DIRECTION_OUTBOUND;
      case Tags.SPAN_KIND_INTERNAL:
        return DIRECTION_NONE;
      default:
        return DIRECTION_UNKNOWN;
    }
  }

  /**
   * The name {@code entry} is emitted under in the OpenTelemetry namespace on a span of {@code
   * direction}: {@code peer.port} is {@code server.port} on a client span. A custom tag keeps its
   * own name.
   */
  public static String openTelemetryTagOf(TagMap.EntryReader entry, int direction) {
    long tagId = entry.tagId();
    return tagId == 0 ? entry.tag() : KnownTagCodec.openTelemetryTagOf(tagId, direction);
  }

  /**
   * Prepares a write of {@code alias} to a span of {@code direction}. Returns the id of the tag to
   * store the value under; {@link KnownTagCodec#NO_TAG_IN_DIRECTION_SENTINEL} when {@code alias} is
   * a direction-scoped alias that can't resolve yet, so its value is stored as written, pending; or
   * 0 when it is no alias at all, so its value is an ordinary custom tag. Callers that keep a
   * pending-alias flag set it only for the sentinel, so a name that merely passed {@link
   * KnownTagCodec#mayBeScopedAlias}'s filter doesn't slow the span down.
   */
  public static long prepareAliasWrite(TagMap tags, String alias, int direction) {
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
  public static void prepareWrite(TagMap tags, long tagId, int direction) {
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(tags, tagId, direction);
    }
  }

  /**
   * True if a span must route {@code entry} through {@link #prepareWrite} or {@link
   * #prepareAliasWrite}: a tag with an alias, or an alias. A mask test for a known tag; a bit test
   * on the hash, almost always, for a custom one.
   */
  public static boolean isDirectionDependent(TagMap.EntryReader entry) {
    long tagId = entry.tagId();
    if (tagId != 0) {
      return KnownTagCodec.hasScopedAlias(tagId);
    }
    String tag = entry.tag();
    return KnownTagCodec.mayBeScopedAlias(tag)
        && KnownTagCodec.keyOrAliasOf(tag) == KnownTagCodec.DIRECTION_SCOPED_OTEL_NAME_SENTINEL;
  }

  /**
   * Removes the aliases a write of {@code tagId} supersedes: the one it has in {@code direction},
   * or, with no direction yet, any it could have.
   */
  private static void removePendingAliases(TagMap tags, long tagId, int direction) {
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
  public static Object get(TagMap tags, String name, int direction) {
    long key = KnownTagCodec.keyOrAliasOf(name);
    if (key > 0) {
      return get(tags, key, direction);
    }
    TagMap.Entry asWritten = tags.getEntryResolved(0L, name);
    if (key == 0 || asWritten != null) {
      return asWritten == null ? null : asWritten.objectValue();
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    TagMap.Entry tag = tagId > 0 ? tags.getEntry(tagId) : null;
    return tag == null ? null : tag.objectValue();
  }

  /** The value of the known tag {@code tagId} on a span of {@code direction}; see {@link #get}. */
  public static Object get(TagMap tags, long tagId, int direction) {
    if (KnownTagCodec.hasScopedAlias(tagId) && direction != DIRECTION_UNKNOWN) {
      String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
      Object pending = alias == null ? null : tags.getObject(alias);
      if (pending != null) {
        return pending;
      }
    }
    TagMap.Entry tag = tags.getEntry(tagId);
    return tag == null ? null : tag.objectValue();
  }

  /** Removes {@code name} from a span of {@code direction}: the tag and its alias together. */
  public static void remove(TagMap tags, String name, int direction) {
    tags.remove(name);
    if (direction == DIRECTION_UNKNOWN) {
      removeEveryMeaningOf(tags, name);
      return;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    if (tagId > 0) {
      tags.getAndRemove(tagId);
    } else {
      String alias = KnownTagCodec.directionScopedNameOf(name, direction);
      if (alias != null) {
        tags.remove(alias);
      }
    }
  }

  /** Removes the known tag {@code tagId}, and its alias, from a span of {@code direction}. */
  public static void remove(TagMap tags, long tagId, int direction) {
    tags.getAndRemove(tagId);
    if (KnownTagCodec.hasScopedAlias(tagId)) {
      removePendingAliases(tags, tagId, direction);
    }
  }

  /**
   * A removal before the span has a direction counts as a write of every tag {@code name} could
   * denote once it has one, so no older value can resurface when the kind arrives: removing a tag
   * also removes every pending alias that could denote it, and removing an alias also removes every
   * tag it could denote. Like the write rule, this can drop the other direction's tag.
   */
  private static void removeEveryMeaningOf(TagMap tags, String name) {
    long tagId = KnownTagCodec.keyOf(name);
    if (tagId != 0) {
      if (KnownTagCodec.hasScopedAlias(tagId)) {
        removePendingAliases(tags, tagId, DIRECTION_UNKNOWN);
      }
      return;
    }
    if (!KnownTagCodec.mayBeScopedAlias(name)) {
      return;
    }
    for (int direction = DIRECTION_INBOUND; direction <= DIRECTION_NONE; direction++) {
      long aliasedId = KnownTagCodec.directionalKeyOf(name, direction);
      if (aliasedId > 0) {
        tags.getAndRemove(aliasedId);
      }
    }
  }

  /**
   * Resolves each pending alias to its tag, for a span of {@code direction} about to be emitted.
   * The alias, being the newer write, overwrites its tag. An alias with no tag in {@code direction}
   * -- or any, while the direction is unknown -- stays as written, a custom tag.
   */
  public static void resolveScopedNames(TagMap tags, int direction) {
    if (direction == DIRECTION_UNKNOWN || direction == DIRECTION_NONE) {
      return; // no alias names a tag on a span with no direction, or an internal one
    }
    List<TagMap.Entry> pending = null;
    for (TagMap.EntryReader entry : tags) {
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
      tags.set(KnownTagCodec.directionalKeyOf(entry.tag(), direction), entry.objectValue());
    }
  }

  private SpanTags() {}
}

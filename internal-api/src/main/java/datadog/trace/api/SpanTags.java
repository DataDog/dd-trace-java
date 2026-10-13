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
   * Prepares a write of {@code name} to a span of {@code direction}, returning the id to store the
   * value under, or 0 to store it under {@code name}. An alias the direction resolves becomes its
   * tag's id; a write of a tag that has an alias removes the pending aliases it supersedes. Every
   * other name costs one small map probe.
   */
  public static long prepareWrite(TagMap tags, String name, int direction) {
    long role = KnownTagCodec.directionRoleOf(name);
    if (role == KnownTagCodec.NOT_DIRECTION_DEPENDENT) {
      return 0L;
    }
    if (role == 0) {
      long tagId = KnownTagCodec.directionalKeyOf(name, direction);
      if (tagId <= 0) {
        return 0L; // no direction yet, or no tag in it: stored as written
      }
      tags.remove(name); // a value pending under the alias is older than this write
      return tagId;
    }
    removePendingAliases(tags, role, direction);
    return 0L;
  }

  /** {@link #prepareWrite(TagMap, String, int)} for a write of the known tag {@code tagId}. */
  public static void prepareWrite(TagMap tags, long tagId, int direction) {
    if (KnownTagCodec.hasDirectionScopedName(tagId)) {
      removePendingAliases(tags, tagId, direction);
    }
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
   * reads its alias; a pending alias, being the newer write, wins. Every other name costs one small
   * map probe more than {@link TagMap#getObject}.
   */
  public static Object get(TagMap tags, String name, int direction) {
    long role = KnownTagCodec.directionRoleOf(name);
    if (role == KnownTagCodec.NOT_DIRECTION_DEPENDENT) {
      return tags.getObject(name);
    }
    if (role == 0) {
      Object pending = tags.getObject(name);
      long tagId = KnownTagCodec.directionalKeyOf(name, direction);
      if (pending != null || tagId <= 0) {
        return pending;
      }
      TagMap.Entry tag = tags.getEntry(tagId);
      return tag == null ? null : tag.objectValue();
    }
    return get(tags, role, direction);
  }

  /** The value of the known tag {@code tagId} on a span of {@code direction}; see {@link #get}. */
  public static Object get(TagMap tags, long tagId, int direction) {
    if (direction != DIRECTION_UNKNOWN && KnownTagCodec.hasDirectionScopedName(tagId)) {
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
    String alias = KnownTagCodec.directionScopedNameOf(tagId, direction);
    if (alias != null) {
      tags.remove(alias);
    }
  }

  /**
   * Resolves each pending alias to its tag, for a span of {@code direction} about to be emitted.
   * The alias, being the newer write, overwrites its tag. An alias with no tag in {@code direction}
   * -- or any, while the direction is unknown -- stays as written, a custom tag.
   */
  public static void resolveScopedNames(TagMap tags, int direction) {
    if (direction == DIRECTION_UNKNOWN) {
      return;
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

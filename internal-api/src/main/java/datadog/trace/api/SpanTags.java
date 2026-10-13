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
 * applied here, by the span layer, when a tag is read, removed or emitted.
 *
 * <p>A tag is stored once, under its id, whatever the direction: {@code peer.port} is one tag that
 * is emitted as {@code client.port} inbound and {@code server.port} outbound. A direction-scoped
 * OpenTelemetry name ({@code server.address}, which is {@code http.hostname} inbound and {@code
 * peer.hostname} outbound) cannot be resolved until the span has a direction, so a value written
 * under it is stored under that name, and resolved by the rule {@code (name, direction) -> tag}.
 * When both the tag and its scoped name hold a value, the tag's wins.
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
   * The value of {@code name} on a span of {@code direction}: a direction-scoped name reads its
   * tag, and a tag reads a value written under its scoped name. Costs one small switch more than
   * {@link TagMap#getObject} for any other name.
   */
  public static Object get(TagMap tags, String name, int direction) {
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    if (tagId > 0) {
      TagMap.Entry tag = tags.getEntry(tagId);
      return tag != null ? tag.objectValue() : tags.getObject(name);
    }
    Object value = tags.getObject(name);
    if (value != null || direction == DIRECTION_UNKNOWN) {
      return value;
    }
    String scopedName = KnownTagCodec.directionScopedNameOf(name, direction);
    return scopedName == null ? null : tags.getObject(scopedName);
  }

  /**
   * The value of the known tag {@code tagId}, or of its scoped name on a span of {@code direction}.
   */
  public static Object get(TagMap tags, long tagId, int direction) {
    TagMap.Entry tag = tags.getEntry(tagId);
    if (tag != null || direction == DIRECTION_UNKNOWN) {
      return tag == null ? null : tag.objectValue();
    }
    String scopedName = KnownTagCodec.directionScopedNameOf(tagId, direction);
    return scopedName == null ? null : tags.getObject(scopedName);
  }

  /**
   * Removes {@code name} from a span of {@code direction}: the tag and its scoped name together.
   */
  public static void remove(TagMap tags, String name, int direction) {
    tags.remove(name);
    if (direction == DIRECTION_UNKNOWN) {
      return;
    }
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    if (tagId > 0) {
      tags.getAndRemove(tagId);
    } else {
      String scopedName = KnownTagCodec.directionScopedNameOf(name, direction);
      if (scopedName != null) {
        tags.remove(scopedName);
      }
    }
  }

  /** Removes the known tag {@code tagId}, and its scoped name, from a span of {@code direction}. */
  public static void remove(TagMap tags, long tagId, int direction) {
    tags.getAndRemove(tagId);
    String scopedName = KnownTagCodec.directionScopedNameOf(tagId, direction);
    if (scopedName != null) {
      tags.remove(scopedName);
    }
  }

  /**
   * Resolves each value written under a direction-scoped name to its tag, for a span of {@code
   * direction} about to be emitted: the tag takes the value unless it holds one already, and the
   * scoped name is dropped either way. A name with no tag in {@code direction} -- or any, while the
   * direction is unknown -- stays as written, a custom tag.
   */
  public static void resolveScopedNames(TagMap tags, int direction) {
    if (direction == DIRECTION_UNKNOWN) {
      return;
    }
    List<TagMap.Entry> scoped = null;
    for (TagMap.EntryReader entry : tags) {
      if (entry.tagId() == 0 && KnownTagCodec.directionalKeyOf(entry.tag(), direction) > 0) {
        if (scoped == null) {
          scoped = new ArrayList<>(2);
        }
        scoped.add(entry.entry());
      }
    }
    if (scoped == null) {
      return;
    }
    for (TagMap.Entry entry : scoped) {
      tags.remove(entry.tag());
      long tagId = KnownTagCodec.directionalKeyOf(entry.tag(), direction);
      if (tags.getEntry(tagId) == null) {
        tags.set(tagId, entry.objectValue());
      }
    }
  }

  private SpanTags() {}
}

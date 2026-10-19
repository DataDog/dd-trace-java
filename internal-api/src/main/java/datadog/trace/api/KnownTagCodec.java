package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.Tags;

/**
 * Resolves generated tag IDs and names. {@link KnownTags} owns the lookup tables; this class owns
 * the ID layout and namespace naming policy.
 *
 * <p>Datadog and OpenTelemetry names can resolve to the same ID. {@link #canonicalTagName(String)}
 * provides the Datadog key used by {@link TagMap}; the namespace readers choose output names. An ID
 * marks a tag that may be intercepted ({@link #INTERCEPTED}), but {@code TagInterceptor} decides
 * whether to consume the value. The ID says nothing about where the tag is stored.
 */
public final class KnownTagCodec {
  /*
   * tagId bit layout: [63-48 serialNum (16 bits)] [47-32 slot] [31-0 flags]. serialNum is
   * globally unique per known tag and is the whole of the tag's identity — nameOf/
   * openTelemetryNameOf switch on it, and the generator emits each id as a literal. Bits [47-32]
   * hold the tag's slot ({@link #slotOf}), assigned by graph coloring so tags on one span type never
   * share a slot, or {@link #NO_SLOT} for a tag on no modeled span type. Of the low 32 flag bits, bit 2 is
   * the trace/span LEVEL bit (set ⟹ trace-level), and bit 1 is the INTERCEPTED bit. Unknown
   * (string-only) custom tags are NOT known ids — {@code keyOf} returns 0 for them.
   *
   * <p>There is one id per Datadog name. A tag the conventions declare once per direction, such as
   * {@code peer.port}, is still one tag: only its OpenTelemetry name depends on the span's
   * direction, so direction applies when a tag is named, never when it is stored.
   *
   * <p>The INTERCEPTED bit marks a tag TagInterceptor may route on the set-path, to a span field or
   * a sampling directive instead of tag storage. It is a hint, not a decision: the interceptor's
   * switch decides, and may still store the tag. A setter called with a constant id tests the bit
   * at JIT time, so the interception path folds away for every tag without it.
   *
   * <p>There is deliberately NO OpenTelemetry-applicability flag: an absent otel-name means
   * pass-through (the tag is emitted under its Datadog name), so today every known tag has an
   * OpenTelemetry name and such a flag would be constant. It returns once a Datadog-only tag exists.
   *
   * <p>Serials are assigned at build time and are NOT stable across releases: they follow the tags'
   * names in order, so adding a tag renumbers others. Never persist or transmit a raw id.
   */
  public static int serialNum(long tagId) {
    return (int) (tagId >>> 48);
  }

  /**
   * Trace/span LEVEL bit (low-32 carve, bit 2). Set marks a trace-level tag (lives on the
   * TraceSegment's own TagMap); clear marks a span-level tag. Declared in the conventions as the
   * {@code trace_level} tier, so it is part of the tag's identity rather than of any storage
   * scheme.
   */
  public static final long LEVEL_TRACE = 1L << 2;

  /** True if the tagId names a trace-level tag. */
  public static boolean isTraceLevel(long tagId) {
    return (tagId & LEVEL_TRACE) != 0L;
  }

  /**
   * INTERCEPTED bit (low-32 carve, bit 1): marks a tag TagInterceptor may route on the set-path.
   * The tracer overlay, {@code tag-conventions-java.yaml}, lists these tags.
   */
  public static final long INTERCEPTED = 1L << 1;

  /** True if the tagId names a tag TagInterceptor may route on the set-path. */
  public static boolean isIntercepted(long tagId) {
    return (tagId & INTERCEPTED) != 0L;
  }

  /** Bit position of the slot: bits [47-32] of an id. */
  public static final int SLOT_SHIFT = 32;

  /** The slot of a known tag on no modeled span type. */
  public static final int NO_SLOT = 0xFFFF;

  /**
   * The tag's slot, or {@link #NO_SLOT}. The generator colors tags that appear together on a span
   * type with distinct slots, so when instrumentation follows its span type, {@code 1L <<
   * slotOf(id)} gives each of a span's tags its own presence bit -- except that on a span type with
   * more tags than slots, its optional tags share slots with each other. A span can still hold two
   * tags that share a slot, so a clear bit means the tag is absent, but a set bit only means it may
   * be present. Every tag without a slot shares bit 63, which no real slot uses.
   */
  public static int slotOf(long tagId) {
    return (int) (tagId >>> SLOT_SHIFT) & 0xFFFF;
  }

  /**
   * True if the tag has a slot, below {@link #slotCount()}. False for {@code 0L}, the id {@link
   * #keyOf} gives a custom tag, which would otherwise decode as slot 0.
   */
  public static boolean hasSlot(long tagId) {
    return tagId != 0L && slotOf(tagId) != NO_SLOT;
  }

  /** Slots in use: every slotted tag's slot is below this. */
  public static int slotCount() {
    return KnownTags.SLOT_COUNT;
  }

  /** Returns the tagId with the {@link #LEVEL_TRACE} flag set. */
  public static long traceLevel(long tagId) {
    return tagId | LEVEL_TRACE;
  }

  /**
   * Builds an id carrying only {@code serialNum}: slot 0 and no flags, so it equals a tag's real id
   * only when that tag has neither. Enough for serial-keyed lookups such as {@link #nameOf};
   * compare against a {@code KnownTags} constant, never against this. Inverse of {@link
   * #serialNum}. Intended for tests and serial-keyed lookups.
   */
  public static long makeTagId(int serialNum) {
    return (long) serialNum << 48;
  }

  /*
   * A span's direction, which names the few tags whose OpenTelemetry name depends on it: peer.port
   * is client.port on inbound spans and server.port on outbound ones. A tag is stored the same way
   * in every direction; direction applies only when naming it. Pass one of these to
   * openTelemetryTagOf(long, int).
   */

  /** Direction not known, for example a span with no kind: only names that need none apply. */
  public static final int DIRECTION_UNKNOWN = -1;

  /** Server and consumer spans, which receive a request or message. */
  public static final int DIRECTION_INBOUND = 0;

  /** Client and producer spans, which send a request or message. */
  public static final int DIRECTION_OUTBOUND = 1;

  /** Internal spans, which have no other end. */
  public static final int DIRECTION_NONE = 2;

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

  /** The registry's tables. Generated as {@code KnownTags.RESOLVER}; this class owns the policy. */
  public interface Resolver {
    /** The tag's Datadog-namespace (canonical) name. */
    String nameOf(long tagId);

    /**
     * The tag's OpenTelemetry-namespace name on spans of {@code direction}, or {@code null} when it
     * declares none there. {@link #DIRECTION_UNKNOWN} returns only a name that holds in every
     * direction.
     */
    String openTelemetryNameOf(long tagId, int direction);

    /** The id for {@code name} in ANY namespace (many→one), or 0 when it is not a known tag. */
    long keyOf(String name);

    /** See {@link KnownTagCodec#directionalKeyOf}. */
    long directionalKeyOf(String name, int direction);
  }

  /**
   * Loads the generated resolver on the first name lookup. Reading {@code RESOLVER} initializes
   * {@link KnownTags}, so callers cannot observe an unregistered or partially initialized registry.
   * The {@code static final} receiver can also help the JIT inline resolver calls.
   */
  private static final class Installed {
    static final Resolver RESOLVER = KnownTags.RESOLVER;
  }

  /** The tag's canonical (Datadog-namespace) name, or {@code null} when the id is not known. */
  public static String nameOf(long tagId) {
    return Installed.RESOLVER.nameOf(tagId);
  }

  /** The tag's Datadog-namespace (canonical) name — the same value as {@link #nameOf}. */
  public static String datadogNameOf(long tagId) {
    return nameOf(tagId);
  }

  /**
   * The tag's declared OpenTelemetry RENAME, or {@code null} when it declares none. Raw registry
   * data — it does not apply the pass-through default, so most callers want {@link
   * #openTelemetryTagOf} instead.
   */
  public static String openTelemetryNameOf(long tagId) {
    return Installed.RESOLVER.openTelemetryNameOf(tagId, DIRECTION_UNKNOWN);
  }

  /**
   * The name {@code tagId} is emitted under in the OpenTelemetry namespace: its declared rename
   * when it has one, otherwise its Datadog name — pass-through, the default. {@code null} for an
   * unknown id, which has no registry name at all; a custom tag falls back to its own key, and only
   * the caller holding that key can do so.
   *
   * <p>This is the one place the pass-through policy lives, so no serializer re-decides it. Pair it
   * with {@link #datadogNameOf} for the same tag under the Datadog namespace; outbound naming is
   * per-namespace, never normalized to one of them.
   *
   * <p>Needs no direction for nearly every tag. The exception is a tag renamed in some directions
   * only ({@code http.hostname} is {@code server.address} on inbound spans, {@code peer.port} is
   * {@code client.port} inbound and {@code server.port} outbound): without a direction it passes
   * through under its Datadog name. A caller naming a span's tags uses {@link
   * #openTelemetryTagOf(long, int)}.
   */
  public static String openTelemetryTagOf(long tagId) {
    return openTelemetryTagOf(tagId, DIRECTION_UNKNOWN);
  }

  /**
   * {@link #openTelemetryTagOf(long)} on spans of {@code direction}, which also applies a tag's
   * rename that holds only in that direction: {@code http.hostname} is {@code server.address} on
   * inbound spans, but stays {@code http.hostname} on outbound ones.
   */
  public static String openTelemetryTagOf(long tagId, int direction) {
    Resolver resolver = Installed.RESOLVER;
    String otelName = resolver.openTelemetryNameOf(tagId, direction);
    return otelName != null ? otelName : resolver.nameOf(tagId);
  }

  /**
   * One past the largest known serial: an array of this length, indexed by {@link #serialNum}, has
   * a slot for every known tag (slot 0 is no tag).
   */
  public static int serialLimit() {
    return KnownTags.SERIAL_LIMIT;
  }

  /**
   * True if {@code tagId} names a known tag. Gives the same answer as {@code nameOf(tagId) !=
   * null}, but as a range check on the serial, which folds away for a constant id.
   */
  public static boolean isKnown(long tagId) {
    int serial = serialNum(tagId);
    return serial != 0 && serial < serialLimit(); // serial 0 is no tag
  }

  /** The id for {@code name} in any namespace, or 0 when it is not a known tag. */
  public static long keyOf(String name) {
    return Installed.RESOLVER.keyOf(name);
  }

  /**
   * The Datadog-namespace name to store {@code name} under: {@code name} itself when it is not a
   * known tag, otherwise the canonical Datadog name for whichever id it resolves to. A Datadog name
   * maps to itself (no-op); an OpenTelemetry rename maps to the Datadog name it is a rename of.
   *
   * <p>{@link TagMap} calls this at entry construction so that setting a known tag under its
   * Datadog name and under its OpenTelemetry rename store to the same {@code Entry} rather than two
   * separate ones -- the two names denote one tag, and only serialization ({@link #datadogNameOf},
   * {@link #openTelemetryTagOf}) should see them as different.
   */
  public static String canonicalTagName(String name) {
    long id = keyOf(name);
    return id != 0 ? nameOf(id) : name;
  }

  private KnownTagCodec() {}

  /**
   * The tag an OpenTelemetry name denotes on spans of {@code direction}, for a name whose tag
   * depends on direction ({@code server.address} is {@code http.hostname} inbound and {@code
   * peer.hostname} outbound); 0 for every other name, and for such a name on spans of a direction
   * where it names no tag, or when {@code name} is null. The core stores and reads Datadog names
   * only; this is for the OpenTelemetry side, which knows its span's direction.
   */
  public static long directionalKeyOf(String name, int direction) {
    // a null attribute key comes from the application; leave it for the span builder to ignore
    return name == null ? 0L : Installed.RESOLVER.directionalKeyOf(name, direction);
  }

  /** True if {@code name} denotes a different tag depending on the span's direction. */
  public static boolean isDirectionScoped(String name) {
    for (int direction = DIRECTION_INBOUND; direction <= DIRECTION_NONE; direction++) {
      if (directionalKeyOf(name, direction) != 0) {
        return true;
      }
    }
    return false;
  }
}

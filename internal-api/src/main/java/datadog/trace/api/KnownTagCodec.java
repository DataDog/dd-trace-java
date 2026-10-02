package datadog.trace.api;

/**
 * Resolves generated tag IDs and names. {@link KnownTags} owns the lookup tables; this class owns
 * the ID layout and namespace naming policy.
 *
 * <p>Datadog and OpenTelemetry names can resolve to the same ID. {@link #canonicalTagName(String)}
 * provides the Datadog key used by {@link TagMap}; the namespace readers choose output names. The
 * ID does not determine whether a tag is intercepted or where it is stored.
 */
public final class KnownTagCodec {
  /*
   * tagId bit layout: [63-48 serialNum (16 bits, 15 used: bit 63 is kept clear)] [47-32 reserved,
   * zero] [31-0 flags]. Keeping bit 63 clear makes every id positive, so 0 (unknown) and the
   * negative lookup sentinels below can never be ids; the generator enforces it. serialNum is
   * globally unique per known tag and is the whole of the tag's identity — nameOf/
   * openTelemetryNameOf switch on it, and the generator emits each id as a literal. Bits [47-32]
   * are RESERVED and always zero here: they are the window the dense tag store uses for its
   * co-occurrence slot coordinate, which arrives with that store. Of the low 32 flag bits, bit 2 is
   * the trace/span LEVEL bit (set ⟹ trace-level); bits 1-0 are reserved. Unknown (string-only)
   * custom tags are NOT known ids — {@code keyOf} returns 0 for them.
   *
   * <p>An id says what a tag IS, not how it is SET. Whether the tracer intercepts a tag on the
   * set-path — routing it to a span field or a sampling directive instead of tag storage — belongs
   * to TagInterceptor, whose {@code needsIntercept} switch is the authority; mirroring it here as a
   * classification bit and a serial-range tier only created drift between the two. That
   * classification returns with the work that consumes it (the id→handler dispatch table that
   * retires TagInterceptor), and re-adding a bit then is purely additive.
   *
   * <p>There is deliberately NO OpenTelemetry-applicability flag: an absent otel-name means
   * pass-through (the tag is emitted under its Datadog name), so today every known tag has an
   * OpenTelemetry name and such a flag would be constant. It returns once a Datadog-only tag exists.
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

  /** Returns the tagId with the {@link #LEVEL_TRACE} flag set. */
  public static long traceLevel(long tagId) {
    return tagId | LEVEL_TRACE;
  }

  /**
   * Builds a tagId from its {@code serialNum} (globally unique per known tag). The reserved [47-32]
   * window and the low 32 bits are zero, so the id is fully determined by the serial — the
   * generator emits it as a literal. Inverse of {@link #serialNum}. Intended for the code generator
   * and tests.
   */
  public static long makeTagId(int serialNum) {
    return (long) serialNum << 48;
  }

  /*
   * A span's direction, which resolves the few names whose tag depends on it: peer.port is the
   * client's port on inbound spans and the server's on outbound ones. Pass one of these to
   * keyOf(String, int) and openTelemetryTagOf(long, int).
   */

  /** Direction not known, for example a span with no kind: only names that need none resolve. */
  public static final int DIRECTION_UNKNOWN = -1;

  /** Server and consumer spans, which receive a request or message. */
  public static final int DIRECTION_INBOUND = 0;

  /** Client and producer spans, which send a request or message. */
  public static final int DIRECTION_OUTBOUND = 1;

  /** Internal spans, which have no other end. */
  public static final int DIRECTION_NONE = 2;

  /*
   * Lookup sentinels. Each is negative and distinct; a tag id never is, since the generator keeps
   * every serial below 2^15. Not ids: nothing may store one as a tag.
   */

  /**
   * What {@link Resolver#lookup} returns for a Datadog name shared by a tag per direction ({@code
   * peer.port}): no single tag, but whichever of them a map holds is that name's value.
   */
  static final long SHARED_DATADOG_NAME_SENTINEL = -1L;

  /**
   * What {@link Resolver#lookup} returns for an OpenTelemetry name that applies in one direction
   * only: {@code server.address} is {@code http.hostname} on inbound spans and {@code
   * peer.hostname} on outbound ones.
   */
  static final long DIRECTION_SCOPED_OTEL_NAME_SENTINEL = -2L;

  /**
   * What {@link #directionalKeyOf} returns for a name whose tag depends on the span's direction but
   * that names no tag on spans of the given direction: {@code client.port} on an outbound span, or
   * any such name while the direction is unknown.
   */
  public static final long NO_TAG_IN_DIRECTION_SENTINEL = -3L;

  /** The registry's tables. Generated as {@code KnownTags.RESOLVER}; this class owns the policy. */
  public interface Resolver {
    /** The tag's Datadog-namespace (canonical) name. */
    String nameOf(long tagId);

    /**
     * The tag's OpenTelemetry-namespace name on spans of {@code direction}, or {@code null} when it
     * declares none there. {@link #DIRECTION_UNKNOWN} returns every name that needs no direction:
     * one that holds in every direction, or that of a tag declared per direction.
     */
    String openTelemetryNameOf(long tagId, int direction);

    /**
     * The id for {@code name} in any namespace; {@link #SHARED_DATADOG_NAME_SENTINEL} or {@link
     * #DIRECTION_SCOPED_OTEL_NAME_SENTINEL} when its tag depends on direction; 0 when it is not a
     * known tag.
     */
    long lookup(String name);

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
   * <p>Needs no direction for nearly every tag, including one declared per direction ({@code
   * peer.port} on an outbound span is {@code server.port}). The exception is a single tag whose
   * rename holds in one direction only ({@code http.hostname} is {@code server.address} on inbound
   * spans): without a direction it passes through under its Datadog name. A caller naming a span's
   * tags uses {@link #openTelemetryTagOf(long, int)}.
   */
  public static String openTelemetryTagOf(long tagId) {
    return openTelemetryTagOf(tagId, DIRECTION_UNKNOWN);
  }

  /**
   * {@link #openTelemetryTagOf(long)} on spans of {@code direction}, which also applies a single
   * tag's rename that holds only in that direction: {@code http.hostname} is {@code server.address}
   * on inbound spans, but stays {@code http.hostname} on outbound ones.
   */
  public static String openTelemetryTagOf(long tagId, int direction) {
    Resolver resolver = Installed.RESOLVER;
    String otelName = resolver.openTelemetryNameOf(tagId, direction);
    return otelName != null ? otelName : resolver.nameOf(tagId);
  }

  /**
   * The id for {@code name} in any namespace, or 0 when it is not a known tag. A name whose tag
   * depends on the span's direction also resolves to 0; see {@link #keyOf(String, int)}.
   */
  public static long keyOf(String name) {
    long key = Installed.RESOLVER.lookup(name);
    return key < 0 ? 0L : key;
  }

  /**
   * The id for {@code name} on spans of {@code direction}, or 0 when it is not a known tag there.
   * Costs the same as {@link #keyOf(String)} for every name but the few that depend on direction.
   */
  public static long keyOf(String name, int direction) {
    Resolver resolver = Installed.RESOLVER;
    long key = resolver.lookup(name);
    if (key >= 0) {
      return key;
    }
    long tagId = resolver.directionalKeyOf(name, direction);
    return tagId > 0 ? tagId : 0L;
  }

  /**
   * Only the direction-dependent part of {@link #keyOf(String, int)}, for a caller that already
   * resolves every other name: the tag {@code name} denotes on spans of {@code direction} when it
   * is one of the few names that depend on direction ({@code peer.port}, {@code server.address}); 0
   * for every other name; or {@link #NO_TAG_IN_DIRECTION_SENTINEL}.
   *
   * <p>A switch over just those names, so a miss is far cheaper than a full lookup. A span uses it
   * before handing a name to {@link TagMap}, which then does its usual single lookup.
   */
  public static long directionalKeyOf(String name, int direction) {
    return Installed.RESOLVER.directionalKeyOf(name, direction);
  }

  /**
   * The raw registry lookup: {@code name}'s id, 0 when it is not a known tag, or {@link
   * #SHARED_DATADOG_NAME_SENTINEL} / {@link #DIRECTION_SCOPED_OTEL_NAME_SENTINEL} when its tag
   * depends on direction.
   */
  static long lookup(String name) {
    return Installed.RESOLVER.lookup(name);
  }

  /**
   * {@link #keyOf(String)}, except that a Datadog name shared by a tag per direction returns {@link
   * #SHARED_DATADOG_NAME_SENTINEL}, so {@link TagMap} can find whichever of those tags it holds,
   * through {@link #directionalKeyOf}. One lookup, like {@code keyOf}.
   */
  static long keyOrSharedName(String name) {
    long key = Installed.RESOLVER.lookup(name);
    return key == DIRECTION_SCOPED_OTEL_NAME_SENTINEL ? 0L : key;
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
}

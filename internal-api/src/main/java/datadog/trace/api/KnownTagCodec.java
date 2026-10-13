package datadog.trace.api;

import java.util.HashMap;
import java.util.Map;

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
   * the trace/span LEVEL bit (set ⟹ trace-level), and bit 1 is the INTERCEPTED bit. Unknown (string-only) custom tags are NOT known ids — {@code keyOf} returns 0 for
   * them.
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
   * A span's direction, which names the few tags whose OpenTelemetry name depends on it: peer.port
   * is client.port on inbound spans and server.port on outbound ones. A tag is stored the same way
   * in every direction; direction applies only when naming it. Pass one of these to
   * openTelemetryTagOf(long, int) and directionalKeyOf(String, int).
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
     * declares none there. {@link #DIRECTION_UNKNOWN} returns only a name that holds in every
     * direction.
     */
    String openTelemetryNameOf(long tagId, int direction);

    /**
     * The id for {@code name} in any namespace; {@link #DIRECTION_SCOPED_OTEL_NAME_SENTINEL} when
     * its tag depends on direction; 0 when it is not a known tag.
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
   * The tag a direction-scoped OpenTelemetry name ({@code server.address}, {@code client.port})
   * denotes on spans of {@code direction}; 0 for every other name; or {@link
   * #NO_TAG_IN_DIRECTION_SENTINEL}. A value written under such a name is stored as written and
   * resolved to its tag here, once the span's direction is known.
   *
   * <p>A switch over just those names, so a miss is far cheaper than a full lookup.
   */
  public static long directionalKeyOf(String name, int direction) {
    return Installed.RESOLVER.directionalKeyOf(name, direction);
  }

  /**
   * The inverse of {@link #directionalKeyOf}: the direction-scoped OpenTelemetry name {@code tagId}
   * has on spans of {@code direction} ({@code server.address} for {@code http.hostname} inbound),
   * or null when it has none there, or its name there holds in every direction.
   */
  public static String directionScopedNameOf(long tagId, int direction) {
    if (direction == DIRECTION_UNKNOWN) {
      return null;
    }
    Resolver resolver = Installed.RESOLVER;
    String name = resolver.openTelemetryNameOf(tagId, direction);
    return name != null && resolver.openTelemetryNameOf(tagId, DIRECTION_UNKNOWN) == null
        ? name
        : null;
  }

  /**
   * {@link #directionScopedNameOf(long, int)} by Datadog name. Costs one small map probe for every
   * name but the few tags renamed in some directions only.
   */
  public static String directionScopedNameOf(String ddName, int direction) {
    if (direction == DIRECTION_UNKNOWN) {
      return null;
    }
    long role = directionRoleOf(ddName);
    return role > 0 ? directionScopedNameOf(role, direction) : null;
  }

  /** What {@link #directionRoleOf} returns for a name that does not depend on direction. */
  static final long NOT_DIRECTION_DEPENDENT = -1L;

  /**
   * How {@code name} takes part in direction-scoped naming: 0 for a direction-scoped OpenTelemetry
   * name ({@code server.address}); the id of a tag that has one ({@code peer.hostname}); or {@link
   * #NOT_DIRECTION_DEPENDENT} for every other name. One small map probe.
   */
  static long directionRoleOf(String name) {
    Long role = DirectionScoped.ROLES.get(name);
    return role == null ? NOT_DIRECTION_DEPENDENT : role;
  }

  /** True if {@code tagId} has a direction-scoped OpenTelemetry name; an array load. */
  static boolean hasDirectionScopedName(long tagId) {
    int serial = serialNum(tagId);
    boolean[] bySerial = DirectionScoped.BY_SERIAL;
    return serial < bySerial.length && bySerial[serial];
  }

  /**
   * The names that depend on direction, built once from the registry: each direction-scoped
   * OpenTelemetry name, and each tag that has one.
   */
  private static final class DirectionScoped {
    static final boolean[] BY_SERIAL = new boolean[serialLimit()];
    static final Map<String, Long> ROLES = directionRoles();

    private static Map<String, Long> directionRoles() {
      Map<String, Long> roles = new HashMap<>();
      for (int serial = 1; serial < serialLimit(); serial++) {
        String name = nameOf(makeTagId(serial));
        long tagId = keyOf(name);
        for (int direction = DIRECTION_INBOUND; direction <= DIRECTION_NONE; direction++) {
          String scopedName = directionScopedNameOf(tagId, direction);
          if (scopedName != null) {
            BY_SERIAL[serial] = true;
            roles.put(name, tagId);
            roles.put(scopedName, 0L);
          }
        }
      }
      return roles;
    }
  }

  /**
   * The raw registry lookup: {@code name}'s id, 0 when it is not a known tag, or {@link
   * #DIRECTION_SCOPED_OTEL_NAME_SENTINEL} when its tag depends on direction.
   */
  static long lookup(String name) {
    return Installed.RESOLVER.lookup(name);
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

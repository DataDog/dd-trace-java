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
   * tagId bit layout: [63-48 serialNum (16 bits)] [47-32 reserved, zero] [31-0 flags]. serialNum is
   * globally unique per known tag and is the whole of the tag's identity — nameOf/
   * openTelemetryNameOf switch on it, and the generator emits each id as a literal. Bits [47-32]
   * are RESERVED and always zero here: they are the window the dense tag store uses for its
   * co-occurrence slot coordinate, which arrives with that store. Of the low 32 flag bits, bit 2 is
   * the trace/span LEVEL bit (set ⟹ trace-level), bit 1 is the INTERCEPTED bit, and bit 0 is
   * reserved. Unknown (string-only) custom tags are NOT known ids — {@code keyOf} returns 0 for
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

  public interface Resolver {
    /** The tag's Datadog-namespace (canonical) name. */
    String nameOf(long tagId);

    /** The tag's OpenTelemetry-namespace name, or {@code null} when it declares none. */
    String openTelemetryNameOf(long tagId);

    /** The id for {@code name} in ANY namespace (many→one), or 0 when it is not a known tag. */
    long keyOf(String name);
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
    return Installed.RESOLVER.openTelemetryNameOf(tagId);
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
   */
  public static String openTelemetryTagOf(long tagId) {
    Resolver resolver = Installed.RESOLVER;
    String otelName = resolver.openTelemetryNameOf(tagId);
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
}

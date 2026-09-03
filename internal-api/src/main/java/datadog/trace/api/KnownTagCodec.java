package datadog.trace.api;

/**
 * Registry for generated tag ID ↔ name resolution. This class and the generated {@code KnownTags}
 * are two halves of one thing: the codec owns the bit layout and the naming policy, {@code
 * KnownTags} owns the name&harr;id tables. {@code Installed} names {@code KnownTags.RESOLVER}
 * directly, so resolving a tag name is what initializes the registry — there is no registration
 * call to make and no ordering to get wrong.
 *
 * <p>Holding the resolver in a {@code static final} of that holder is what makes {@link
 * #nameOf}/{@link #keyOf} effectively zero-overhead: the JIT constant-folds the field to the
 * resolver instance, and a constant receiver has an exact klass, so the call devirtualizes and
 * inlines with no CHA dependency to invalidate.
 *
 * <p>A tag id is IDENTITY, not storage: it names one tag across every namespace the tag is known
 * by. {@link #keyOf} is many→one (a Datadog name or an OpenTelemetry name both resolve to the one
 * id) and the per-namespace readers — {@link #datadogNameOf}, {@link #openTelemetryNameOf} — take
 * it back out. How (or whether) a tag is stored is a separate concern that no part of this class
 * decides.
 */
public final class KnownTagCodec {
  /*
   * tagId bit layout: [63-48 serialNum (16 bits)] [47-32 reserved, zero] [31-0 flags]. serialNum is
   * globally unique per known tag and is the whole of the tag's identity — nameOf/
   * openTelemetryNameOf switch on it, and the generator emits each id as a literal. Bits [47-32]
   * are RESERVED and always zero here: they are the window the dense tag store uses for its
   * co-occurrence slot coordinate, which arrives with that store. Of the low 32 flag bits, bit 2 is
   * the trace/span LEVEL bit (set ⟹ trace-level) and bit 3 is the INTERCEPTED bit (set ⟹ routed on
   * the set-path); bits 1-0 are reserved. Unknown (string-only) custom tags are NOT known ids —
   * {@code keyOf} returns 0 for them, so they are never mistaken for intercepted.
   *
   * <p>Of the low flag bits, bit 3 is the INTERCEPTED bit: set when this tracer routes the tag on
   * the set-path (to a span field or a sampling directive) rather than merely storing it. It is a
   * per-language classification, declared in the Java overlay rather than in the
   * language-agnostic domain spec, and it exists for speed: a stored TagMap entry carries its own
   * tag id, so screening a bundle for anything the interceptor cares about is a mask test on an id
   * already in hand — no name lookup and no side table. An earlier version of this bit was deleted
   * because it could disagree with TagInterceptor's switch; it is back because that agreement is
   * now a test (see TagInterceptorRoutingTest) rather than a convention. Note it says only that the
   * tag is routed, never whether it is also stored — {@code http.url} is both, and which of the two
   * happens is decided per call from the value.
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
   * Set-path ROUTING bit (low-32 carve, bit 3). Set marks a tag this tracer diverts on the set path
   * — to a span field, a metric, or a sampling directive — as declared in the Java overlay (its
   * `intercepted` and `reserved` sections). Clear marks a tag that is only ever stored. Being
   * routed does not imply not being stored; that is decided per call from the value.
   */
  public static final long INTERCEPTED = 1L << 3;

  /**
   * True if the tagId names a tag this tracer routes on the set-path. A single mask test, and a
   * stored entry already carries its id, so this is the cheap form of the pre-screen that
   * TagInterceptor's name switch used to do. Returns false for id 0 (an unknown custom tag), which
   * is the right answer: routing is only ever declared for known tags.
   */
  public static boolean isIntercepted(long tagId) {
    return (tagId & INTERCEPTED) != 0L;
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
   * Holder that hands the codec its generated half. {@code KnownTags} is emitted into this very
   * package on the main compile path, so the link is an ordinary compile-time reference: the first
   * read of {@code RESOLVER} initializes this holder, which initializes {@code KnownTags}. Nothing
   * needs to be poked first, and no reader can observe a registry that is not there yet.
   *
   * <p>The nesting is load-bearing. {@code KnownTags} calls back into {@code KnownTagCodec}, so
   * were {@code RESOLVER} a field of the codec itself, the codec's own initializer would re-enter
   * on the same thread and silently read defaults. Holding it one class down means {@code
   * KnownTagCodec}'s initializer is complete before {@code KnownTags}' ever starts.
   *
   * <p>The point of the {@code static final} is the read side. The JIT treats it as a true constant
   * — it folds the load away entirely, and a constant receiver carries an exact klass, so the
   * resolver's switch devirtualizes and inlines outright. So {@link #keyOf} / {@link #nameOf} carry
   * no lock, no volatile read, no null check and no virtual call. HotSpot also elides the
   * class-init barrier once the class is initialized, so the one-shot cost is paid once, ever, and
   * never on a tag path.
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

  /** The id for {@code name} in any namespace, or 0 when it is not a known tag. */
  public static long keyOf(String name) {
    return Installed.RESOLVER.keyOf(name);
  }

  private KnownTagCodec() {}
}

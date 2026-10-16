package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Arrays;

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

  /**
   * HAS_SCOPED_ALIAS bit (low-32 carve, bit 0): marks a tag with an OpenTelemetry name that holds
   * in some directions only ({@code peer.hostname} is {@code server.address} on outbound spans), so
   * a value written under that alias can denote it. A write of a constant id tests the bit at JIT
   * time, so the alias handling folds away for every tag without it.
   */
  public static final long HAS_SCOPED_ALIAS = 1L << 0;

  /** The direction of a span with these tags, from its {@code span.kind}. */
  public static int directionOf(TagMap tags) {
    Object kind = tags.getKnownValue(KnownTags.SPAN_KIND_ID);
    return kind == null ? DIRECTION_UNKNOWN : directionOfKind(kind.toString());
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
   * True if a span must apply its direction to {@code entry}: a known tag with {@link
   * #HAS_SCOPED_ALIAS}, or a direction-scoped alias stored as written. A mask test for a known tag;
   * a filter test, then one lookup, for a custom one.
   */
  public static boolean isDirectionDependent(TagMap.EntryReader entry) {
    long tagId = entry.tagId();
    if (tagId != 0) {
      return hasScopedAlias(tagId);
    }
    String tag = entry.tag();
    return mayBeScopedAlias(tag) && keyOrAliasOf(tag) == DIRECTION_SCOPED_OTEL_NAME_SENTINEL;
  }

  /** True if {@code tagId} has an OpenTelemetry name that holds in some directions only. */
  public static boolean hasScopedAlias(long tagId) {
    return (tagId & HAS_SCOPED_ALIAS) != 0L;
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
   * peer.hostname} on outbound ones. {@link #keyOrAliasOf} returns it too.
   */
  public static final long DIRECTION_SCOPED_OTEL_NAME_SENTINEL = -2L;

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
   * depends on the span's direction also resolves to 0; see {@link #directionalKeyOf}.
   */
  public static long keyOf(String name) {
    long key = Installed.RESOLVER.lookup(name);
    return key < 0 ? 0L : key;
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
    if (direction == DIRECTION_UNKNOWN || !hasScopedAlias(tagId)) {
      return null;
    }
    Resolver resolver = Installed.RESOLVER;
    String name = resolver.openTelemetryNameOf(tagId, direction);
    return name != null && resolver.openTelemetryNameOf(tagId, DIRECTION_UNKNOWN) == null
        ? name
        : null;
  }

  /** {@link #directionScopedNameOf(long, int)} by Datadog name. */
  public static String directionScopedNameOf(String ddName, int direction) {
    if (direction == DIRECTION_UNKNOWN) {
      return null;
    }
    long tagId = keyOf(ddName);
    return tagId != 0 ? directionScopedNameOf(tagId, direction) : null;
  }

  /**
   * {@link #keyOf(String)}, except that a direction-scoped alias ({@code server.address}) returns
   * {@link #DIRECTION_SCOPED_OTEL_NAME_SENTINEL} instead of 0. One lookup, like {@code keyOf}: a
   * span writing a tag by name learns from it both the id to store under and whether the name
   * depends on its direction (the alias sentinel, or an id with {@link #HAS_SCOPED_ALIAS}).
   */
  public static long keyOrAliasOf(String name) {
    return Installed.RESOLVER.lookup(name);
  }

  /**
   * The tag whose Datadog name is {@code name}, even when the name depends on the span's direction
   * ({@code server.port} is also {@code peer.port}'s outbound alias, so {@link #keyOf} returns 0
   * for it); 0 when no tag has that Datadog name. For a span that has no direction yet.
   */
  public static long datadogKeyOf(String name) {
    long key = keyOrAliasOf(name);
    if (key != DIRECTION_SCOPED_OTEL_NAME_SENTINEL) {
      return key < 0 ? 0L : key;
    }
    for (int direction = DIRECTION_INBOUND; direction <= DIRECTION_NONE; direction++) {
      long tagId = directionalKeyOf(name, direction);
      if (tagId > 0 && name.equals(nameOf(tagId))) {
        return tagId;
      }
    }
    return 0L;
  }

  /**
   * True if {@code entry} must yield its emitted name on a span of {@code direction}: it is a tag
   * named for another tag's alias there ({@code server.port}, on a client span that also holds
   * {@code peer.port}). The alias's tag wins, so the span emits each attribute name once.
   */
  public static boolean yieldsName(TagMap tags, TagMap.EntryReader entry, int direction) {
    long tagId = entry.tagId();
    if (tagId == 0 || !hasScopedAlias(tagId) || direction == DIRECTION_UNKNOWN) {
      return false;
    }
    long owner = directionalKeyOf(openTelemetryTagOf(tagId, direction), direction);
    return owner > 0 && owner != tagId && tags.getKnownValue(owner) != null;
  }

  /**
   * True if any entry of {@code tags} {@link #yieldsName yields its name} on spans of {@code
   * direction}.
   */
  public static boolean hasYieldingName(TagMap tags, int direction) {
    if (direction == DIRECTION_UNKNOWN) {
      return false;
    }
    for (long tagId : SharedNames.TAG_IDS) {
      if (tags.getKnownValue(tagId) != null) {
        long owner = directionalKeyOf(nameOf(tagId), direction);
        if (owner > 0 && owner != tagId && tags.getKnownValue(owner) != null) {
          return true;
        }
      }
    }
    return false;
  }

  /** The tags whose Datadog name is also another tag's alias, built once from the registry. */
  private static final class SharedNames {
    static final long[] TAG_IDS = sharedNameTagIds();

    private static long[] sharedNameTagIds() {
      long[] ids = new long[0];
      for (int serial = 1; serial < serialLimit(); serial++) {
        String name = nameOf(makeTagId(serial));
        if (keyOrAliasOf(name) == DIRECTION_SCOPED_OTEL_NAME_SENTINEL) {
          ids = Arrays.copyOf(ids, ids.length + 1);
          ids[ids.length - 1] = datadogKeyOf(name);
        }
      }
      return ids;
    }
  }

  /**
   * A cheap pre-check for a name that may be a direction-scoped alias: false for nearly every other
   * name, at the cost of a bit test on its (cached) hash. A true answer still needs {@link
   * #directionalKeyOf} to confirm.
   */
  public static boolean mayBeScopedAlias(String name) {
    return (AliasFilter.MASK & (1L << name.hashCode())) != 0L;
  }

  /** A 64-bit filter over the direction-scoped aliases' hashes, built once from the registry. */
  private static final class AliasFilter {
    static final long MASK = aliasMask();

    private static long aliasMask() {
      long mask = 0L;
      for (int serial = 1; serial < serialLimit(); serial++) {
        String name = nameOf(makeTagId(serial));
        long tagId = keyOrAliasOf(name);
        if (tagId == DIRECTION_SCOPED_OTEL_NAME_SENTINEL) {
          // A Datadog name that is also an alias (server.port): the tag's only scoped name is its
          // own, which another tag's alias already put in the filter.
          mask |= 1L << name.hashCode();
          continue;
        }
        for (int direction = DIRECTION_INBOUND; direction <= DIRECTION_NONE; direction++) {
          String alias = directionScopedNameOf(tagId, direction);
          if (alias != null) {
            mask |= 1L << alias.hashCode();
          }
        }
      }
      return mask;
    }
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

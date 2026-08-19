package datadog.trace.api;

/**
 * Registry for generated tag ID ↔ name resolution. The code generator populates this at tracer init
 * via {@link #register(Resolver)}. Once registered, HotSpot CHA devirtualizes and inlines the
 * resolver's switch, making {@link #nameOf}/{@link #keyOf} effectively zero-overhead.
 */
public final class KnownTagCodec {
  // Plain (non-volatile) fast-path flag: false until a mapping-bearing resolver is registered (it
  // stays false when the codec freezes as the empty NoKnownTagCodec). A plain read is free and
  // hoistable, unlike a volatile read of `resolver` (costly on weak memory models such as ARM). A
  // stale `false` is benign — callers treat the tag as unknown and use the hash buckets, which is
  // correct, just unoptimized; the next read after publication takes the slot path.
  private static boolean active;

  // The installed codec. There is always conceptually a codec: either a real resolver (via
  // register, at tracer init) or the empty NoKnownTagCodec, lazily installed on first use if
  // nothing
  // was registered. Resolved exactly once, then LOCKED — so a map can never be built half-bucketed
  // then half-dense by a late registration.
  private static volatile Resolver resolver;

  // True once `resolver` is resolved (real via register, or lazy NoKnownTagCodec). Cold-path only.
  private static volatile boolean locked;

  /** Fast-path gate: true once a mapping-bearing resolver has been registered. */
  public static boolean isActive() {
    return active;
  }

  /*
   * tagId bit layout: [63 intercepted] [62-48 globalSerial (15 bits)] [47-32 slot (16 bits)] [31-0
   * reserved, zero]. Bit 63 (the sign bit) marks a tag the tag interceptor must see, so the check is
   * a single {@code tagId < 0}. globalSerial is globally unique per known tag. The middle 16 bits
   * carry the tag's SLOT: one globally stable coordinate assigned by graph-coloring the tag
   * co-occurrence graph (the resolved tag set of each concrete span type, plus the trace-level
   * tier, is a clique). Co-occurring tags always get distinct slots; slots are reused only between
   * tags that never appear together, so slotCount stays bounded by the largest clique (≤ 64) and
   * fits one {@code long} occupancy mask — the dense store's single-tier presence fast path (see
   * {@link TagMap}). The low 32 bits are unused for known ids (the whole id is fully determined by
   * serial + slot, so the generator can emit a literal). The low 32 bits are being carved for
   * cross-cutting flags; bit 2 is the trace/span LEVEL bit (set ⟹ trace-level), and bits 1-0 are
   * reserved for the dd/otel applicability flags that land with increment 1. The level bit lets
   * read-through skip the shadow check across the trace/span boundary — trace and span tags reuse
   * the same slots, so occupancy alone can't tell them apart, but a span map (no trace-level tags)
   * can never shadow a trace-level ancestor entry (see {@link TagMap}). Unknown (string-only) custom
   * tags are NOT known ids — they key off {@code TagMap.Entry#_hash(name)} in their own bucket path
   * and never enter here.
   */
  public static int serialNum(long tagId) {
    return (int) ((tagId >>> 48) & 0x7FFF);
  }

  /**
   * Flag bit (the sign bit) marking a tag the tag interceptor must process — reserved tags AND
   * intercepted-but-stored tags (e.g. http.method, which the interceptor side-effects and also
   * stores). Encoded in the id so {@code DDSpanContext.setTag(long)} can route with a single sign
   * test ({@link #isIntercepted}) instead of resolving the name. Non-intercepted tags (peer.*,
   * base.service, …) leave it clear and take the fast store path. Must agree with the interceptor's
   * name-based {@code needsIntercept} for every assigned id.
   */
  public static final long INTERCEPTED = Long.MIN_VALUE; // 1L << 63

  /** True if the tagId is flagged for tag-interceptor processing. */
  public static boolean isIntercepted(long tagId) {
    return tagId < 0L;
  }

  /** Returns the tagId with the {@link #INTERCEPTED} flag set. */
  public static long intercepted(long tagId) {
    return tagId | INTERCEPTED;
  }

  /**
   * Trace/span LEVEL bit (low-32 carve, bit 2). Set marks a trace-level tag (lives on the
   * TraceSegment's own TagMap); clear marks a span-level tag. Trace and span tags reuse the same
   * coloring slots, so this bit is what lets read-through tell the two levels apart — a span map
   * (no trace-level tags) can never shadow a trace-level ancestor entry, so its shadow check is
   * skipped (see {@link TagMap#parentDenseVisible}).
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

  // The middle 16 bits [47-32] hold the tag's SLOT: one globally stable coordinate from graph
  // coloring the co-occurrence graph. Co-occurring tags get distinct slots and slotCount stays
  // bounded by the largest clique (<= 64), so the dense store's presence fast path is a single
  // occupancy long (1L << slot); a clear bit proves the tag absent and enables an O(1) append. See
  // TagMap's dense-store fast path.
  static final int SLOT_SHIFT = 32;
  static final int SLOT_MASK = 0xFFFF; // 16 bits

  /**
   * The tag's slot: its globally stable coloring coordinate, or {@link #NO_SLOT} when it has none
   * (reserved or deliberately bucket-only). Drives the dense store's single occupancy mask.
   */
  public static int slot(long tagId) {
    return (int) ((tagId >>> SLOT_SHIFT) & SLOT_MASK);
  }

  /**
   * globalSerial partition. {@code [1, FIRST_STORED_SERIAL)} is the RESERVED tier and {@code
   * [FIRST_STORED_SERIAL, ..]} is the STORED tier; {@code globalSerial == 0} means unknown /
   * string-only. Both core and the code generator must agree on this boundary.
   *
   * <p><b>Reserved</b> is the shared mechanism: the tracer reserves the key and handles it itself
   * instead of putting it in the TagMap. It says nothing about whether a value exists — that splits
   * into two kinds (the {@code kind:} in the overlay):
   *
   * <ul>
   *   <li><b>structural</b> — the value <i>does</i> exist, it just lives in a first-class
   *       span/trace field (service, resource.name, error, span.type, origin), not the tag map.
   *   <li><b>directive</b> — there is <i>no</i> stored value; the key is a command that triggers
   *       trace behavior (sampling.priority, manual.keep, measured).
   * </ul>
   *
   * "virtual" over-claims non-existence (wrong for structural) and "built-in" over-claims existence
   * (wrong for directive), so the tier is named for the mechanism they share: reserved. These are
   * hand-assigned in the overlay. <b>Stored</b> tags are the generated convention tags that ARE put
   * in the map (slotted/bucketed).
   */
  public static final int FIRST_STORED_SERIAL = 256;

  /** True if the tagId names a reserved (structural/directive) tag — handled, not stored. */
  public static boolean isReserved(long tagId) {
    int serialNum = serialNum(tagId);
    return serialNum > 0 && serialNum < FIRST_STORED_SERIAL;
  }

  /** True if the tagId names a generated, map-stored (slotted/bucketed) tag. */
  public static boolean isStored(long tagId) {
    return serialNum(tagId) >= FIRST_STORED_SERIAL;
  }

  /**
   * Dense-store routing gate, decoupled from name resolution. The {@link Resolver} is registered
   * unconditionally at tracer init (so {@code keyOf}/{@code nameOf} — and OTel name mapping —
   * always work); this flag, captured once from {@code trace.experimental.dense.tags.enabled},
   * separately decides whether known tags actually take the dense store. As a {@code static final}
   * it constant-folds, so the dense branches in {@link TagMap} dead-code-eliminate when off.
   */
  public static final boolean DENSE_STORE = Config.get().isTraceDenseTagsEnabled();

  /**
   * True iff the tagId should route to the dense store: it names a stored tag AND the dense store
   * is enabled. This is the single predicate {@link TagMap} branches on — {@link #isStored} alone
   * is layout identity, independent of whether dense routing is switched on.
   */
  public static boolean routesToDense(long tagId) {
    return DENSE_STORE && isStored(tagId);
  }

  /**
   * Sentinel {@code slot} meaning "no positional slot". It is the maximum value the 16-bit slot
   * field can hold, so it always compares {@code >= slotCount()} and routes to the hash buckets
   * rather than the fast positional array. Two kinds of tagId use it:
   *
   * <ul>
   *   <li>Reserved tags ({@code globalSerial < FIRST_STORED_SERIAL}) — not stored at all; the
   *       sentinel just guarantees an incidental store never lands in a slot.
   *   <li>Unslotted stored tags ({@code globalSerial >= FIRST_STORED_SERIAL}) — "low-priority" tags
   *       that get a stable id (and so {@code keyOf}/{@code nameOf} unification with their string
   *       form) but are deliberately not given a slot, so they live in the buckets. {@code
   *       getEntry(long)} for these resolves the name and rehashes — the cost of not owning a slot.
   * </ul>
   */
  public static final int NO_SLOT = SLOT_MASK; // slot all-ones sentinel (16 bits)

  /**
   * True if the tagId names a stored tag that deliberately has no positional slot (bucket-only).
   */
  public static boolean isUnslotted(long tagId) {
    return isStored(tagId) && slot(tagId) == NO_SLOT;
  }

  /**
   * Builds a tagId from its {@code serialNum} (globally unique per known tag) and {@code slot} (its
   * coloring coordinate, or {@link #NO_SLOT}). The low 32 bits are zero, so the id is fully
   * determined by these parts — the generator emits it as a literal. Inverse of {@link
   * #serialNum}/{@link #slot}. Intended for the code generator and tests.
   */
  public static long makeTagId(int serialNum, int slot) {
    return ((long) serialNum << 48) | ((long) (slot & SLOT_MASK) << SLOT_SHIFT);
  }

  /**
   * Builds a tagId with no positional slot ({@code slot == }{@link #NO_SLOT}). Use for reserved
   * tags and for "low-priority" stored tags that get a stable id but are intentionally kept out of
   * the fast slot array (they route to the hash buckets). See {@link #NO_SLOT}.
   */
  public static long makeTagId(int serialNum) {
    return makeTagId(serialNum, NO_SLOT);
  }

  // Number of positional slots in the global layout = (max stored fieldPos) + 1, declared by the
  // registered provider. Captured once at registration and read as a dynamic constant; TagMap sizes
  // its knownEntries array to exactly this rather than a hardcoded max. 0 when no resolver.
  private static int slotCount;

  /** Slot count of the registered provider (max stored fieldPos + 1); 0 if none. */
  public static int slotCount() {
    return slotCount;
  }

  public interface Resolver {
    String nameOf(long tagId);

    /** The tag's OpenTelemetry-namespace name, or {@code null} when it declares none. */
    String openTelemetryNameOf(long tagId);

    long keyOf(String name);

    /** Number of positional slots this provider uses: (max stored fieldPos) + 1. */
    int slotCount();
  }

  /**
   * Empty null-object codec: no name&harr;id mappings, no slots. Installed lazily on first use when
   * nothing was registered, so the codec is always present. Its behavior is byte-identical to the
   * pre-registry world — {@code keyOf} returns 0, {@code nameOf} returns null, every tag is unknown
   * and takes the hash buckets.
   */
  private static final class NoKnownTagCodec implements Resolver {
    static final NoKnownTagCodec INSTANCE = new NoKnownTagCodec();

    @Override
    public String nameOf(long tagId) {
      return null;
    }

    @Override
    public String openTelemetryNameOf(long tagId) {
      return null;
    }

    @Override
    public long keyOf(String name) {
      return 0L;
    }

    @Override
    public int slotCount() {
      return 0;
    }
  }

  // active/slotCount are plain by design: written once here at tracer-init registration (before any
  // span processing) and read plain on the hot path. A stale read is benign — the tag is treated as
  // unknown and takes the hash-bucket path — so plain reads are deliberately preferred over a
  // costly
  // volatile read on weak memory models.
  public static synchronized void register(Resolver resolver) {
    if (resolver == null) {
      throw new NullPointerException("resolver");
    }
    if (locked) {
      if (KnownTagCodec.resolver == resolver) {
        return; // idempotent: the same resolver may be registered again (e.g. repeated init())
      }
      throw new IllegalStateException(
          "KnownTagCodec is already locked; a resolver cannot be registered after first use");
    }
    KnownTagCodec.resolver = resolver; // volatile write publishes the resolver
    KnownTagCodec.slotCount = resolver.slotCount();
    KnownTagCodec.locked = true;
    KnownTagCodec.active = true; // plain write; readers re-read resolver volatile anyway
  }

  // Freeze the codec as the empty NoKnownTagCodec when nothing was registered by first use. Keeps
  // `active` false (No has no mappings) so the hot path stays a plain-boolean short-circuit.
  private static synchronized void freezeAsNoCodec() {
    if (locked) {
      return;
    }
    KnownTagCodec.resolver = NoKnownTagCodec.INSTANCE;
    KnownTagCodec.locked = true;
  }

  public static String nameOf(long tagId) {
    if (active) {
      return resolver.nameOf(tagId);
    }
    if (!locked) {
      freezeAsNoCodec();
    }
    return null;
  }

  /** The tag's Datadog-namespace (canonical) name — the same value as {@link #nameOf}. */
  public static String datadogNameOf(long tagId) {
    return nameOf(tagId);
  }

  /**
   * The tag's OpenTelemetry-namespace name, or {@code null} when it declares none (or no resolver
   * is registered). A serializer owns any fall-back-to-Datadog-name policy; this is a pure lookup.
   */
  public static String openTelemetryNameOf(long tagId) {
    if (!active) return null;
    Resolver r = resolver;
    return r != null ? r.openTelemetryNameOf(tagId) : null;
  }

  public static long keyOf(String name) {
    if (active) {
      return resolver.keyOf(name);
    }
    if (!locked) {
      freezeAsNoCodec();
    }
    return 0L;
  }

  private KnownTagCodec() {}
}

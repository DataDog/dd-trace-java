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
   * tagId bit layout: [63 intercepted] [62-48 globalSerial (15 bits)] [47-32 fieldPos]
   * [31-0 nameHash]. Bit 63 (the sign bit) marks a tag the tag interceptor must see, so the check
   * is a single {@code tagId < 0}. globalSerial is globally unique per known tag; fieldPos is its
   * slot in the global positional layout (TagMap.knownEntries index); nameHash is
   * TagMap.Entry#_hash(name) and is layout-independent. Unknown (string-only) tags have the upper
   * 32 bits zero. NOTE: TagMap.Entry decodes nameHash inline as (int) tagId on its hot path, so the
   * low-32 encoding here must stay in sync with that.
   */
  public static int globalSerial(long tagId) {
    return (int) ((tagId >>> 48) & 0x7FFF);
  }

  /**
   * Flag bit (the sign bit) marking a tag the tag interceptor must process — reserved/"virtual"
   * tags AND intercepted-but-stored tags (e.g. http.method, which the interceptor side-effects and
   * also stores). Encoded in the id so {@code DDSpanContext.setTag(long)} can route with a single
   * sign test ({@link #isIntercepted}) instead of resolving the name. Non-intercepted tags (peer.*,
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

  public static int fieldPos(long tagId) {
    return (int) ((tagId >>> 32) & 0xFFFF);
  }

  public static int nameHash(long tagId) {
    return (int) tagId;
  }

  /**
   * globalSerial partition. {@code [1, FIRST_STORED_SERIAL)} is reserved for "virtual" tags that
   * are specially handled (redirected to span fields or processed by the tag interceptor) and are
   * NOT stored in the TagMap — these are hand-assigned in tracer core. {@code [FIRST_STORED_SERIAL,
   * ..]} is for generated convention tags that ARE stored (slotted/bucketed). {@code globalSerial
   * == 0} means unknown / string-only. Both core and the code generator must agree on this
   * boundary.
   */
  public static final int FIRST_STORED_SERIAL = 256;

  /** True if the tagId names a reserved "virtual"/specially-handled tag (not stored in the map). */
  public static boolean isReserved(long tagId) {
    int globalSerial = globalSerial(tagId);
    return globalSerial > 0 && globalSerial < FIRST_STORED_SERIAL;
  }

  /** True if the tagId names a generated, map-stored (slotted/bucketed) tag. */
  public static boolean isStored(long tagId) {
    return globalSerial(tagId) >= FIRST_STORED_SERIAL;
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
   * Sentinel {@code fieldPos} meaning "no positional slot". It is the maximum value the 16-bit
   * fieldPos field can hold, so it always compares {@code >= slotCount()} and routes to the hash
   * buckets rather than the fast positional array. Two kinds of tagId use it:
   *
   * <ul>
   *   <li>Reserved/virtual tags ({@code globalSerial < FIRST_STORED_SERIAL}) — not stored at all;
   *       the sentinel just guarantees an incidental store never lands in a slot.
   *   <li>Unslotted stored tags ({@code globalSerial >= FIRST_STORED_SERIAL}) — "low-priority" tags
   *       that get a stable id (and so {@code keyOf}/{@code nameOf} unification with their string
   *       form) but are deliberately not given a slot, so they live in the buckets and don't widen
   *       {@code knownEntries[]} for every span. {@code getEntry(long)} for these resolves the name
   *       and rehashes — the cost of not owning a slot.
   * </ul>
   */
  public static final int NO_SLOT = 0xFFFF;

  /**
   * True if the tagId names a stored tag that deliberately has no positional slot (bucket-only).
   */
  public static boolean isUnslotted(long tagId) {
    return isStored(tagId) && fieldPos(tagId) == NO_SLOT;
  }

  /**
   * Builds a tagId from its parts: {@code globalSerial} (globally unique per known tag), {@code
   * fieldPos} (the tag's slot within its span type's positional table), and the tag {@code name}
   * (whose hash is computed via the same function the runtime uses, so the low 32 bits match {@link
   * TagMap.Entry#hash()}). Inverse of {@link #globalSerial}/{@link #fieldPos}/{@link #nameHash}.
   * Intended for the code generator and tests.
   */
  public static long tagId(int globalSerial, int fieldPos, String name) {
    long nameHash = TagMap.Entry._hash(name) & 0xFFFFFFFFL;
    return ((long) globalSerial << 48) | ((long) (fieldPos & 0xFFFF) << 32) | nameHash;
  }

  /**
   * Builds a tagId with no positional slot ({@code fieldPos == }{@link #NO_SLOT}). Use for reserved
   * "virtual" tags and for "low-priority" stored tags that get a stable id but are intentionally
   * kept out of the fast slot array (they route to the hash buckets). See {@link #NO_SLOT}.
   */
  public static long tagId(int globalSerial, String name) {
    return tagId(globalSerial, NO_SLOT, name);
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

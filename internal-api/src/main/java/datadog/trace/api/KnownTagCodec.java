package datadog.trace.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Registry for generated tag ID ↔ name resolution. The code generator populates this at tracer init
 * via {@link #register(Resolver)}. Once registered, HotSpot CHA devirtualizes and inlines the
 * resolver's switch, making {@link #nameOf}/{@link #keyOf} effectively zero-overhead.
 */
public final class KnownTagCodec {
  // Plain (non-volatile) fast-path flag: false until a resolver is ever registered. A plain read is
  // free and hoistable, unlike a volatile read of `resolver` (costly on weak memory models such as
  // ARM). A stale `false` is benign — callers treat the tag as unknown and use the hash buckets,
  // which is correct, just unoptimized; the next read after publication takes the slot path.
  private static boolean active;

  private static volatile Resolver resolver;

  /** Fast-path gate: true once a resolver has been registered. */
  public static boolean isActive() {
    return active;
  }

  /*
   * tagId bit layout: [63 intercepted] [62-48 globalSerial (15 bits)] [47-42 group-decl (6 bits)]
   * [41-32 field-decl (10 bits)] [31-0 reserved, zero]. Bit 63 (the sign bit) marks a tag the tag
   * interceptor must see, so the check is a single {@code tagId < 0}. globalSerial is globally
   * unique per known tag. The middle 16 bits carry the tag's DECLARATION coordinate: group-decl is
   * the declaration group (the trace-level tier / each span type / each mixin is a group),
   * field-decl is the tag's ordinal within that group (restarts per group). Together they drive the dense
   * store's two-tier presence fast path (per-map group mask, then field bloom) — see {@link
   * TagMap}. The low 32 bits are unused for known ids (the whole id is fully determined by
   * serial + coordinate, so the generator can emit a literal); they are free for a wider
   * group-decl/field-decl split later. Unknown (string-only) custom tags are NOT known ids — they
   * key off {@code TagMap.Entry#_hash(name)} in their own bucket path and never enter here.
   */
  public static int globalSerial(long tagId) {
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

  // The middle 16 bits [47-32] hold the declaration coordinate, split so the two-tier presence
  // fast path can read each half cheaply: group-decl (high 6 bits, [47-42]) selects the tag's
  // declaration group (<=64 groups -> one long group mask, where a clear bit proves the whole
  // group absent and enables a bulk fast insert); field-decl (low 10 bits, [41-32]) is the tag's
  // ordinal within that group (<=1024 tags/group), which the field bloom hashes (field-decl & 63)
  // once a group is present. See TagMap's dense-store fast path.
  static final int GROUP_DECL_SHIFT = 42;
  static final int GROUP_DECL_MASK = 0x3F; // 6 bits
  static final int FIELD_DECL_SHIFT = 32;
  static final int FIELD_DECL_MASK = 0x3FF; // 10 bits

  /**
   * The tag's declaration group: the trace-level tier, a span type, or a mixin. Assigned by the tag
   * registry; drives the two-tier presence fast path's group mask.
   */
  public static int groupDecl(long tagId) {
    return (int) ((tagId >>> GROUP_DECL_SHIFT) & GROUP_DECL_MASK);
  }

  /** The tag's ordinal within its declaration group. */
  public static int fieldDecl(long tagId) {
    return (int) ((tagId >>> FIELD_DECL_SHIFT) & FIELD_DECL_MASK);
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
    int globalSerial = globalSerial(tagId);
    return globalSerial > 0 && globalSerial < FIRST_STORED_SERIAL;
  }

  /** True if the tagId names a generated, map-stored (slotted/bucketed) tag. */
  public static boolean isStored(long tagId) {
    return globalSerial(tagId) >= FIRST_STORED_SERIAL;
  }

  /**
   * Sentinel {@code field-decl} meaning "no positional slot". It is the maximum value the 10-bit
   * field-decl field can hold, so it always compares {@code >= slotCount()} and routes to the hash
   * buckets rather than the fast positional array. Two kinds of tagId use it:
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
  public static final int NO_SLOT = FIELD_DECL_MASK; // field-decl all-ones sentinel

  /**
   * True if the tagId names a stored tag that deliberately has no positional slot (bucket-only).
   */
  public static boolean isUnslotted(long tagId) {
    return isStored(tagId) && fieldDecl(tagId) == NO_SLOT;
  }

  /**
   * Builds a tagId from all three parts: {@code globalSerial} (globally unique per known tag),
   * {@code groupDecl} (its declaration group), and {@code fieldDecl} (its ordinal within that
   * group). The low 32 bits are zero, so the id is fully determined by these parts — the generator
   * emits it as a literal. Inverse of {@link #globalSerial}/{@link #groupDecl}/{@link #fieldDecl}.
   * Intended for the code generator and tests.
   */
  public static long tagId(int globalSerial, int groupDecl, int fieldDecl) {
    return ((long) globalSerial << 48)
        | ((long) (groupDecl & GROUP_DECL_MASK) << GROUP_DECL_SHIFT)
        | ((long) (fieldDecl & FIELD_DECL_MASK) << FIELD_DECL_SHIFT);
  }

  /** Builds a tagId in group 0 — shorthand for {@link #tagId(int, int, int)} with group-decl 0. */
  public static long tagId(int globalSerial, int fieldDecl) {
    return tagId(globalSerial, 0, fieldDecl);
  }

  /**
   * Builds a tagId with no positional slot ({@code field-decl == }{@link #NO_SLOT}). Use for
   * reserved tags and for "low-priority" stored tags that get a stable id but are intentionally
   * kept out of the fast slot array (they route to the hash buckets). See {@link #NO_SLOT}.
   */
  public static long tagId(int globalSerial) {
    return tagId(globalSerial, NO_SLOT);
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

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "active/slotCount are plain by design: written once at tracer-init registration (before"
              + " any span processing) and read plain on the hot path. A stale read is benign — the"
              + " tag is treated as unknown and takes the hash-bucket path — so plain reads are"
              + " deliberately preferred over a costly volatile read on weak memory models.")
  public static void register(Resolver resolver) {
    KnownTagCodec.resolver = resolver; // volatile write publishes the resolver
    KnownTagCodec.slotCount = (resolver != null) ? resolver.slotCount() : 0;
    KnownTagCodec.active =
        (resolver != null); // plain write; readers re-read resolver volatile anyway
  }

  public static String nameOf(long tagId) {
    if (!active) return null;
    Resolver r = resolver;
    return r != null ? r.nameOf(tagId) : null;
  }

  public static long keyOf(String name) {
    if (!active) return 0L;
    Resolver r = resolver;
    return r != null ? r.keyOf(name) : 0L;
  }

  private KnownTagCodec() {}
}

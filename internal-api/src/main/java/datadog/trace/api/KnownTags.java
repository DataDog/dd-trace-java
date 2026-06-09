package datadog.trace.api;

/**
 * Registry for generated tag ID ↔ name resolution. The code generator populates this at tracer init
 * via {@link #register(Resolver)}. Once registered, HotSpot CHA devirtualizes and inlines the
 * resolver's switch, making {@link #nameOf}/{@link #keyOf} effectively zero-overhead.
 */
public final class KnownTags {
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
   * tagId bit layout: [63-48 globalSerial] [47-32 fieldPos] [31-0 nameHash].
   * globalSerial is globally unique per known tag; fieldPos is the slot within a single span
   * type's positional table (layout-relative — only meaningful within its own Prototype); nameHash
   * is TagMap.Entry#_hash(name) and is layout-independent. Unknown (string-only) tags have the
   * upper 32 bits zero. NOTE: TagMap.Entry decodes nameHash inline as (int) tagId on its hot path,
   * so the low-32 encoding here must stay in sync with that.
   */
  public static int globalSerial(long tagId) {
    return (int) (tagId >>> 48);
  }

  public static int fieldPos(long tagId) {
    return (int) ((tagId >>> 32) & 0xFFFF);
  }

  public static int nameHash(long tagId) {
    return (int) tagId;
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

  public interface Resolver {
    String nameOf(long tagId);

    long keyOf(String name);
  }

  public static void register(Resolver resolver) {
    KnownTags.resolver = resolver; // volatile write publishes the resolver
    KnownTags.active = (resolver != null); // plain write; readers re-read resolver volatile anyway
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

  private KnownTags() {}
}

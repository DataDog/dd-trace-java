package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises the dense known-tag store with a LIVE resolver. Registration ({@link KnownTagCodec}) is
 * a global static with no un-register, so this lives in a {@code ForkedTest} (isolated JVM) to keep
 * dense routing from leaking into the bucket-only tests in the shared JVM. The dense store is
 * dormant in production (no resolver) — this is where it actually executes.
 *
 * <p>Stored tags (globalSerial &ge; {@code FIRST_STORED_SERIAL}) route to the dense store; reserved
 * tags (e.g. {@code error}) and arbitrary tags stay in the hash buckets. Behavior must be
 * observationally identical to the bucket store.
 */
class TagMapDenseForkedTest {

  static {
    // Dense routing is a const-folded gate (KnownTagCodec.DENSE_STORE, captured from Config at
    // KnownTagCodec's class-init). Set the flag before anything touches Config/KnownTagCodec so the
    // gate reads true in this forked JVM. Registering the resolver alone no longer engages dense.
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "true");
  }

  // stored (dense-routed) tags
  static final String BASE_SERVICE = DDTags.BASE_SERVICE;
  static final String COMPONENT = Tags.COMPONENT;
  static final String DB_TYPE = Tags.DB_TYPE;
  static final String HTTP_METHOD = Tags.HTTP_METHOD; // stored + intercepted
  static final String DB_INSTANCE = Tags.DB_INSTANCE;
  // arbitrary (bucket-routed) tags
  static final String CUSTOM_A = "custom.tag.a";
  static final String CUSTOM_B = "custom.tag.b";

  @BeforeAll
  static void registerResolver() {
    // Generated ids are compile-time constants (literal), so a constant reference is inlined and
    // never triggers KnownTags.<clinit>. init() forces class-load -> KnownTagCodec.register.
    KnownTags.init();
    assertTrue(KnownTagCodec.isActive(), "resolver must be live for the dense store to engage");
    assertTrue(KnownTagCodec.DENSE_STORE, "dense store must be enabled in this forked JVM");
    assertTrue(
        KnownTagCodec.routesToDense(KnownTagCodec.keyOf(BASE_SERVICE)),
        "base_service routes dense");
    assertFalse(
        KnownTagCodec.routesToDense(KnownTagCodec.keyOf(CUSTOM_A)), "custom tag stays in buckets");
    assertFalse(
        KnownTagCodec.routesToDense(KnownTagCodec.keyOf(Tags.ERROR)),
        "error is reserved, not stored");
  }

  private static TagMap map() {
    return (TagMap) TagMap.create();
  }

  @Test
  void knownTagRoundTripsThroughDenseStore() {
    TagMap map = map();
    map.set(BASE_SERVICE, "billing");
    map.set(COMPONENT, "spring-web");

    assertEquals("billing", map.getObject(BASE_SERVICE));
    assertEquals("spring-web", map.getString(COMPONENT));
    assertEquals("billing", map.getEntry(BASE_SERVICE).objectValue());
    assertTrue(map.containsKey(BASE_SERVICE));
    assertEquals(2, map.size());
    map.checkIntegrity();
  }

  @Test
  void typedKnownValuesRoundTrip() {
    TagMap map = map();
    map.set(DB_TYPE, "postgresql");
    map.set(HTTP_METHOD, "GET");
    map.set(Tags.PEER_PORT, 5432);

    assertEquals("postgresql", map.getString(DB_TYPE));
    assertEquals("GET", map.getString(HTTP_METHOD));
    assertEquals(5432, map.getInt(Tags.PEER_PORT));
    assertEquals(3, map.size());
    map.checkIntegrity();
  }

  @Test
  void knownAndUnknownCoexist() {
    TagMap map = map();
    map.set(BASE_SERVICE, "billing"); // dense
    map.set(CUSTOM_A, "alpha"); // bucket
    map.set(DB_TYPE, "h2"); // dense
    map.set(CUSTOM_B, "beta"); // bucket

    assertEquals("billing", map.getObject(BASE_SERVICE));
    assertEquals("alpha", map.getObject(CUSTOM_A));
    assertEquals("h2", map.getObject(DB_TYPE));
    assertEquals("beta", map.getObject(CUSTOM_B));
    assertEquals(4, map.size());
    assertFalse(map.isEmpty());
    map.checkIntegrity();

    Map<String, Object> collected = new HashMap<>();
    map.fillMap(collected);
    assertEquals(4, collected.size());
    assertEquals("billing", collected.get(BASE_SERVICE));
    assertEquals("alpha", collected.get(CUSTOM_A));
    assertEquals("h2", collected.get(DB_TYPE));
    assertEquals("beta", collected.get(CUSTOM_B));
  }

  @Test
  void overwriteKnownReplacesInPlace() {
    TagMap map = map();
    map.set(COMPONENT, "first");
    assertEquals("first", map.getObject(COMPONENT));
    map.set(COMPONENT, "second");
    assertEquals("second", map.getObject(COMPONENT));
    assertEquals(1, map.size()); // overwrite, not append
    map.checkIntegrity();
  }

  @Test
  void removeKnownClearsIt() {
    TagMap map = map();
    map.set(BASE_SERVICE, "billing");
    map.set(DB_TYPE, "h2");
    map.set(CUSTOM_A, "alpha");
    assertEquals(3, map.size());

    TagMap.Entry removed = map.getAndRemove(BASE_SERVICE);
    assertEquals("billing", removed.objectValue());
    assertNull(map.getObject(BASE_SERVICE));
    assertEquals("h2", map.getObject(DB_TYPE)); // sibling dense entry intact
    assertEquals("alpha", map.getObject(CUSTOM_A));
    assertEquals(2, map.size());
    map.checkIntegrity();
  }

  @Test
  void forEachAndIteratorEmitDenseAndBucketEntries() {
    TagMap map = map();
    map.set(BASE_SERVICE, "billing");
    map.set(COMPONENT, "web");
    map.set(CUSTOM_A, "alpha");

    Map<String, Object> viaForEach = new HashMap<>();
    map.forEach(reader -> viaForEach.put(reader.tag(), reader.objectValue()));
    assertEquals(3, viaForEach.size());
    assertEquals("billing", viaForEach.get(BASE_SERVICE));
    assertEquals("web", viaForEach.get(COMPONENT));
    assertEquals("alpha", viaForEach.get(CUSTOM_A));

    Map<String, Object> viaIterator = new HashMap<>();
    for (TagMap.EntryReader reader : map) {
      viaIterator.put(reader.tag(), reader.objectValue());
    }
    assertEquals(viaForEach, viaIterator);
  }

  @Test
  void copyPreservesDenseStore() {
    TagMap map = map();
    map.set(BASE_SERVICE, "billing");
    map.set(CUSTOM_A, "alpha");

    TagMap copy = (TagMap) map.copy();
    assertEquals("billing", copy.getObject(BASE_SERVICE));
    assertEquals("alpha", copy.getObject(CUSTOM_A));
    assertEquals(2, copy.size());

    // independence: mutating the copy doesn't touch the original's dense store
    copy.set(BASE_SERVICE, "shipping");
    assertEquals("shipping", copy.getObject(BASE_SERVICE));
    assertEquals("billing", map.getObject(BASE_SERVICE));
    copy.checkIntegrity();
    map.checkIntegrity();
  }

  @Test
  void clearEmptiesDenseStore() {
    TagMap map = map();
    map.set(BASE_SERVICE, "billing");
    map.set(CUSTOM_A, "alpha");
    map.clear();
    assertEquals(0, map.size());
    assertTrue(map.isEmpty());
    assertNull(map.getObject(BASE_SERVICE));
    map.checkIntegrity();
  }

  @Test
  void putAllMergesDenseStore() {
    TagMap src = map();
    src.set(BASE_SERVICE, "billing");
    src.set(DB_TYPE, "h2");
    src.set(CUSTOM_A, "alpha");

    TagMap dst = map();
    dst.set(COMPONENT, "web"); // dense, distinct
    dst.set(BASE_SERVICE, "old"); // dense, clobbered by src
    dst.putAll((TagMap) src);

    assertEquals("billing", dst.getObject(BASE_SERVICE)); // src clobbers
    assertEquals("h2", dst.getObject(DB_TYPE));
    assertEquals("web", dst.getObject(COMPONENT));
    assertEquals("alpha", dst.getObject(CUSTOM_A));
    assertEquals(4, dst.size());
    dst.checkIntegrity();
  }

  // ---- read-through union (dense parent + dense child) ----

  private static TagMap frozenParent() {
    TagMap parent = map();
    parent.set(BASE_SERVICE, "billing"); // dense
    parent.set(COMPONENT, "web"); // dense
    parent.set(CUSTOM_A, "alpha"); // bucket
    parent.freeze();
    return parent;
  }

  @Test
  void childReadsThroughToParentDense() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set(DB_TYPE, "h2"); // child-only dense
    child.set(CUSTOM_B, "beta"); // child-only bucket

    // inherited from parent
    assertEquals("billing", child.getObject(BASE_SERVICE));
    assertEquals("web", child.getObject(COMPONENT));
    assertEquals("alpha", child.getObject(CUSTOM_A));
    // own
    assertEquals("h2", child.getObject(DB_TYPE));
    assertEquals("beta", child.getObject(CUSTOM_B));
    // union size: 3 parent + 2 child
    assertEquals(5, child.size());
    assertFalse(child.isEmpty());

    Map<String, Object> union = new HashMap<>();
    child.forEach(reader -> union.put(reader.tag(), reader.objectValue()));
    assertEquals(5, union.size());
    assertEquals("billing", union.get(BASE_SERVICE));
    assertEquals("h2", union.get(DB_TYPE));
    child.checkIntegrity();
  }

  @Test
  void childDenseShadowsParentDense() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set(BASE_SERVICE, "shipping"); // shadows parent's dense base_service

    assertEquals("shipping", child.getObject(BASE_SERVICE)); // local wins
    assertEquals("web", child.getObject(COMPONENT)); // still inherited
    assertEquals(3, child.size()); // base_service counted once (shadowed, not doubled)

    Map<String, Object> union = new HashMap<>();
    child.forEach(reader -> union.put(reader.tag(), reader.objectValue()));
    assertEquals(3, union.size());
    assertEquals("shipping", union.get(BASE_SERVICE)); // shadow value, parent suppressed
  }

  @Test
  void removingParentDenseKeyTombstonesIt() {
    TagMap child = TagMap.createFromParent(frozenParent());

    TagMap.Entry removed = child.getAndRemove(BASE_SERVICE); // parent-only dense key
    assertEquals("billing", removed.objectValue()); // prior visible value was the parent's
    assertNull(child.getObject(BASE_SERVICE)); // tombstoned: no read-through
    assertEquals("web", child.getObject(COMPONENT)); // sibling still inherited
    assertEquals(2, child.size()); // 3 parent - 1 tombstoned

    Map<String, Object> union = new HashMap<>();
    child.forEach(reader -> union.put(reader.tag(), reader.objectValue()));
    assertEquals(2, union.size());
    assertFalse(union.containsKey(BASE_SERVICE));
    child.checkIntegrity();
  }

  @Test
  void denseReaderExposesTagId() {
    TagMap map = map();
    map.set(BASE_SERVICE, "billing"); // dense-routed
    map.set(CUSTOM_A, "alpha"); // bucket-routed

    Map<String, Long> idsByTag = new HashMap<>();
    map.forEach(reader -> idsByTag.put(reader.tag(), reader.tagId()));

    // dense entry: the reader carries the real known-tag id directly
    assertEquals(KnownTagCodec.keyOf(BASE_SERVICE), idsByTag.get(BASE_SERVICE).longValue());
    assertTrue(idsByTag.get(BASE_SERVICE) != 0L, "known tag has a non-zero id");
    // bucket entry for a custom tag: unknown -> 0L
    assertEquals(0L, idsByTag.get(CUSTOM_A).longValue());
  }

  @Test
  void bucketEntryResolvesTagIdLazily() {
    TagMap map = map();
    map.set(CUSTOM_A, "alpha"); // custom tag stays a bucket Entry

    TagMap.Entry entry = map.getEntry(CUSTOM_A);
    assertEquals(0L, entry.tagId()); // unknown tag -> 0L
    assertEquals(0L, entry.tagId()); // second read hits the memoized field
  }
}

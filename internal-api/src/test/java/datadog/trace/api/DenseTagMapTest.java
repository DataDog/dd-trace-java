package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * TagMap's dense store, built through {@link TagMapType#DENSE} so it runs in the normal test JVM
 * whatever the dense-store flag says. The shared TagMap suites also run against it; this covers
 * what is specific to the dense store. {@link TagMapDenseForkedTest} covers what needs the flag on.
 *
 * <p>A known tag with a dense-store slot is stored densely; a known tag without a slot and a custom
 * tag stay in the buckets. Behavior must match the bucket-only store.
 */
class DenseTagMapTest {
  // slotted, span-level
  static final String COMPONENT = KnownTags.COMPONENT_NAME;
  static final String DB_TYPE = KnownTags.DB_TYPE_NAME;
  static final String HTTP_ROUTE = KnownTags.HTTP_ROUTE_NAME;
  // slotted, trace-level
  static final String BASE_SERVICE = KnownTags.DD_BASE_SERVICE_NAME;
  // known, no slot
  static final String DB_POOL_NAME = KnownTags.DB_POOL_NAME;
  static final String UNSLOTTED_KNOWN = KnownTags.RESOURCE_NAME; // on no span type: no slot
  // custom
  static final String CUSTOM_A = "custom.tag.a";
  static final String CUSTOM_B = "custom.tag.b";

  /** Two slotted span-level tags that the registry gave the same slot. */
  static long sharedSlotA;

  static long sharedSlotB;

  @BeforeAll
  static void checkSetup() throws IllegalAccessException {
    assertTrue(KnownTagCodec.hasSlot(KnownTags.COMPONENT_ID));
    assertTrue(KnownTagCodec.hasSlot(KnownTags.DD_BASE_SERVICE_ID));
    assertFalse(KnownTagCodec.hasSlot(KnownTags.RESOURCE_NAME_ID), "resource.name has no slot");

    Map<Integer, Long> bySlot = new HashMap<>();
    for (Field field : KnownTags.class.getFields()) {
      if (field.getType() != long.class) continue;
      long id = field.getLong(null);
      if (!KnownTagCodec.hasSlot(id) || KnownTagCodec.isTraceLevel(id)) continue;
      if (KnownTagCodec.keyOf(KnownTagCodec.nameOf(id)) != id) continue; // skip shared names
      Long other = bySlot.putIfAbsent(KnownTagCodec.slotOf(id), id);
      if (other != null) {
        sharedSlotA = other;
        sharedSlotB = id;
        return;
      }
    }
    throw new AssertionError("no two slotted tags share a slot");
  }

  private static int knownCount(TagMap map) {
    try {
      Field f = DenseTagMap.class.getDeclaredField("knownCount");
      f.setAccessible(true);
      return f.getInt(map);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** A field of DenseTagMap, or of TagMap for the shared bucket state. */
  private static Object field(Object target, String name) {
    for (Class<?> c = DenseTagMap.class; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
      } catch (NoSuchFieldException e) {
        // look in the superclass
      } catch (ReflectiveOperationException e) {
        throw new AssertionError(e);
      }
    }
    throw new AssertionError("no field " + name);
  }

  @Test
  void aSlottedKnownTagIsStoredDenselyAndRoundTrips() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "spring-web");
    map.set(BASE_SERVICE, "billing");

    assertEquals(2, knownCount(map));
    assertEquals("spring-web", map.getObject(COMPONENT));
    assertEquals("billing", map.getString(BASE_SERVICE));
    assertEquals("spring-web", map.getEntry(COMPONENT).objectValue());
    assertEquals(KnownTags.COMPONENT_ID, map.getEntry(COMPONENT).tagId());
    assertTrue(map.containsKey(COMPONENT));
    assertEquals(2, map.size());
    map.checkIntegrity();
  }

  @Test
  void customTagsStayInBucketsAndUnslottedKnownTagsAreDense() {
    TagMap map = TagMapType.DENSE.create();
    map.set(CUSTOM_A, "alpha");
    map.set(UNSLOTTED_KNOWN, "pool-1"); // a known tag with no slot

    assertEquals(1, knownCount(map));
    assertEquals("alpha", map.getObject(CUSTOM_A));
    assertEquals("pool-1", map.getObject(UNSLOTTED_KNOWN));
    assertEquals(2, map.size());
    map.checkIntegrity();
  }

  @Test
  void typedValuesRoundTrip() {
    TagMap map = TagMapType.DENSE.create();
    map.set(KnownTags.HTTP_STATUS_CODE_NAME, 404);
    map.set(KnownTags.HTTP_RESEND_COUNT_NAME, 3L);
    map.set(KnownTags.DD_PROFILING_ENABLED_NAME, true);
    map.set(KnownTags.HTTP_ROUTE_ID, (CharSequence) "/users/{id}");

    assertEquals(404, map.getInt(KnownTags.HTTP_STATUS_CODE_NAME));
    assertEquals(3L, map.getLong(KnownTags.HTTP_RESEND_COUNT_NAME));
    assertTrue(map.getBoolean(KnownTags.DD_PROFILING_ENABLED_NAME));
    assertEquals("/users/{id}", map.getString(HTTP_ROUTE));
    assertEquals(4, knownCount(map));
    map.checkIntegrity();
  }

  @Test
  void idKeyedSetGetAndRemove() {
    TagMap map = TagMapType.DENSE.create();
    map.set(KnownTags.DB_TYPE_ID, "postgresql");

    assertEquals("postgresql", map.getEntry(KnownTags.DB_TYPE_ID).objectValue());
    assertEquals("postgresql", map.getObject(DB_TYPE));
    assertEquals("postgresql", map.getAndRemove(KnownTags.DB_TYPE_ID).objectValue());
    assertNull(map.getEntry(KnownTags.DB_TYPE_ID));
    assertEquals(0, map.size());
    map.checkIntegrity();
  }

  @Test
  void anOpenTelemetryNameLandsOnTheSameDenseValue() {
    TagMap map = TagMapType.DENSE.create();
    map.set(KnownTags.HTTP_METHOD_OTEL_NAME, "GET");

    assertEquals(1, knownCount(map));
    assertEquals("GET", map.getObject(KnownTags.HTTP_METHOD_NAME));
    assertEquals("GET", map.getObject(KnownTags.HTTP_METHOD_OTEL_NAME));
    map.set(KnownTags.HTTP_METHOD_NAME, "POST");
    assertEquals(1, map.size());
    assertEquals("POST", map.getObject(KnownTags.HTTP_METHOD_OTEL_NAME));
  }

  @Test
  void twoTagsSharingASlotInOneMapBothWork() {
    assertEquals(KnownTagCodec.slotOf(sharedSlotA), KnownTagCodec.slotOf(sharedSlotB));
    TagMap map = TagMapType.DENSE.create();
    map.set(sharedSlotA, "a");
    map.set(sharedSlotB, "b");

    assertEquals(2, knownCount(map));
    assertEquals("a", map.getEntry(sharedSlotA).objectValue());
    assertEquals("b", map.getEntry(sharedSlotB).objectValue());

    map.set(sharedSlotA, "a2");
    assertEquals(2, map.size());
    assertEquals("a2", map.getEntry(sharedSlotA).objectValue());

    assertEquals("a2", map.getAndRemove(sharedSlotA).objectValue());
    assertNull(map.getEntry(sharedSlotA));
    assertEquals("b", map.getEntry(sharedSlotB).objectValue());
    map.checkIntegrity();
  }

  @Test
  void overwriteReplacesInPlaceAndGetAndSetReturnsThePrior() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "first");
    TagMap.Entry prior = map.getAndSet(COMPONENT, "second");

    assertEquals("first", prior.objectValue());
    assertEquals("second", map.getObject(COMPONENT));
    assertEquals(1, map.size());
    map.checkIntegrity();
  }

  @Test
  void removeClearsADenseValue() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "web");
    map.set(CUSTOM_A, "alpha");

    assertEquals("web", map.remove((Object) COMPONENT));
    assertNull(map.getObject(COMPONENT));
    assertFalse(map.remove(COMPONENT));
    assertEquals(1, map.size());
    map.checkIntegrity();
  }

  @Test
  void forEachIteratorAndEntrySetEmitBothRegions() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "web");
    map.set(BASE_SERVICE, "billing");
    map.set(CUSTOM_A, "alpha");
    map.set(DB_POOL_NAME, "pool-1");

    Map<String, Object> viaForEach = new HashMap<>();
    map.forEach(reader -> viaForEach.put(reader.tag(), reader.objectValue()));
    Map<String, Object> viaBiForEach = new HashMap<>();
    map.forEach(viaBiForEach, (m, reader) -> m.put(reader.tag(), reader.objectValue()));
    Map<String, Object> viaIterator = new HashMap<>();
    for (TagMap.EntryReader reader : map) {
      viaIterator.put(reader.tag(), reader.objectValue());
    }
    Map<String, Object> viaEntrySet = new HashMap<>(map);
    Map<String, Object> viaFill = new HashMap<>();
    map.fillMap(viaFill);

    assertEquals(4, viaForEach.size());
    assertEquals("web", viaForEach.get(COMPONENT));
    assertEquals(viaForEach, viaBiForEach);
    assertEquals(viaForEach, viaIterator);
    assertEquals(viaForEach, viaEntrySet);
    assertEquals(viaForEach, viaFill);
    assertEquals(4, map.keySet().size());
    assertTrue(map.values().contains("billing"));
  }

  @Test
  void entrySetEntriesAndEntryCopiesAreRetainSafe() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "web");
    map.set(DB_TYPE, "h2");

    List<Map.Entry<String, Object>> entries = new ArrayList<>(map.entrySet());
    List<TagMap.Entry> copies = new ArrayList<>();
    for (TagMap.EntryReader reader : map) {
      copies.add(reader.entry());
    }

    Map<String, Object> fromEntries = new HashMap<>();
    for (Map.Entry<String, Object> e : entries) fromEntries.put(e.getKey(), e.getValue());
    Map<String, Object> fromCopies = new HashMap<>();
    for (TagMap.Entry e : copies) fromCopies.put(e.tag(), e.objectValue());

    assertEquals(2, fromEntries.size());
    assertEquals("web", fromEntries.get(COMPONENT));
    assertEquals("h2", fromEntries.get(DB_TYPE));
    assertEquals(fromEntries, fromCopies);
  }

  @Test
  void anAllDenseMapAllocatesNoBuckets() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "web");
    map.set(DB_TYPE, "h2");
    Object emptyBuckets = field(null, "EMPTY_BUCKETS");

    assertSame(emptyBuckets, field(map, "buckets"));
    TagMap copy = map.copy();
    assertSame(emptyBuckets, field(copy, "buckets"));

    map.set(CUSTOM_A, "alpha");
    assertNotEquals(emptyBuckets, field(map, "buckets"));
    for (Object bucket : (Object[]) emptyBuckets) {
      assertNull(bucket, "the shared empty buckets were written");
    }
    map.clear();
    assertSame(emptyBuckets, field(map, "buckets"));
  }

  @Test
  void copyIsIndependent() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "web");
    map.set(CUSTOM_A, "alpha");

    TagMap copy = map.copy();
    copy.set(COMPONENT, "grpc");
    copy.remove(CUSTOM_A);

    assertEquals("web", map.getObject(COMPONENT));
    assertEquals("alpha", map.getObject(CUSTOM_A));
    assertEquals("grpc", copy.getObject(COMPONENT));
    assertEquals(1, copy.size());
    map.checkIntegrity();
    copy.checkIntegrity();
  }

  @Test
  void clearEmptiesTheDenseStore() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "web");
    map.set(CUSTOM_A, "alpha");
    map.clear();

    assertEquals(0, map.size());
    assertTrue(map.isEmpty());
    assertTrue(map.isDefinitelyEmpty());
    assertNull(map.getObject(COMPONENT));
    map.set(COMPONENT, "again");
    assertEquals("again", map.getObject(COMPONENT));
    map.checkIntegrity();
  }

  @Test
  void putAllMergesTheDenseStore() {
    TagMap src = TagMapType.DENSE.create();
    src.set(BASE_SERVICE, "billing");
    src.set(DB_TYPE, "h2");
    src.set(CUSTOM_A, "alpha");

    TagMap dst = TagMapType.DENSE.create();
    dst.set(COMPONENT, "web");
    dst.set(BASE_SERVICE, "old");
    dst.putAll(src);

    assertEquals("billing", dst.getObject(BASE_SERVICE));
    assertEquals("h2", dst.getObject(DB_TYPE));
    assertEquals("web", dst.getObject(COMPONENT));
    assertEquals("alpha", dst.getObject(CUSTOM_A));
    assertEquals(4, dst.size());
    dst.checkIntegrity();

    TagMap empty = TagMapType.DENSE.create();
    empty.putAll(src);
    assertEquals(3, empty.size());
    assertEquals("h2", empty.getObject(DB_TYPE));
    empty.checkIntegrity();
  }

  @Test
  void aFrozenDenseMapRejectsWrites() {
    TagMap map = TagMapType.DENSE.create();
    map.set(COMPONENT, "web");
    TagMap frozen = map.immutableCopy();

    assertThrows(IllegalStateException.class, () -> frozen.set(COMPONENT, "x"));
    assertThrows(IllegalStateException.class, () -> frozen.set(KnownTags.DB_TYPE_ID, "x"));
    assertThrows(IllegalStateException.class, () -> frozen.remove(COMPONENT));
    assertEquals("web", frozen.getObject(COMPONENT));
  }

  // ---- read-through: dense values in a frozen parent ----

  private static TagMap frozenParent() {
    TagMap parent = TagMapType.DENSE.create();
    parent.set(BASE_SERVICE, "billing"); // dense, trace-level
    parent.set(COMPONENT, "web"); // dense
    parent.set(CUSTOM_A, "alpha"); // bucket
    return parent.freeze();
  }

  @Test
  void aChildReadsThroughToItsParentsDenseValues() {
    TagMap child = TagMapType.DENSE.createFromParent(frozenParent());
    child.set(DB_TYPE, "h2");
    child.set(CUSTOM_B, "beta");

    assertEquals("billing", child.getObject(BASE_SERVICE));
    assertEquals("web", child.getObject(COMPONENT));
    assertEquals("alpha", child.getObject(CUSTOM_A));
    assertEquals("h2", child.getObject(DB_TYPE));
    assertEquals(5, child.size());
    assertEquals(5, child.estimateSize());

    Map<String, Object> viaForEach = new HashMap<>();
    child.forEach(reader -> viaForEach.put(reader.tag(), reader.objectValue()));
    Map<String, Object> viaIterator = new HashMap<>();
    for (TagMap.EntryReader reader : child) viaIterator.put(reader.tag(), reader.objectValue());
    Map<String, Object> viaFill = new HashMap<>();
    child.fillMap(viaFill);

    assertEquals(5, viaForEach.size());
    assertEquals(viaForEach, viaIterator);
    assertEquals(viaForEach, viaFill);
    child.checkIntegrity();
  }

  @Test
  void aChildsDenseValueShadowsItsParents() {
    TagMap child = TagMapType.DENSE.createFromParent(frozenParent());
    child.set(BASE_SERVICE, "shipping");
    child.set(COMPONENT, "grpc");

    assertEquals("shipping", child.getObject(BASE_SERVICE));
    assertEquals("grpc", child.getObject(COMPONENT));
    assertEquals(3, child.size());

    Map<String, Object> union = new HashMap<>();
    child.forEach(reader -> union.put(reader.tag(), reader.objectValue()));
    assertEquals(3, union.size());
    assertEquals("shipping", union.get(BASE_SERVICE));
    assertEquals("grpc", union.get(COMPONENT));

    List<String> iterated = new ArrayList<>();
    for (TagMap.EntryReader reader : child) iterated.add(reader.tag());
    assertEquals(3, iterated.size());
  }

  @Test
  void removingAParentsDenseValueTombstonesIt() {
    TagMap child = TagMapType.DENSE.createFromParent(frozenParent());

    TagMap.Entry removed = child.getAndRemove(BASE_SERVICE);
    assertNotNull(removed);
    assertEquals("billing", removed.objectValue());
    assertNull(child.getObject(BASE_SERVICE));
    assertEquals("web", child.getObject(COMPONENT));
    assertEquals(2, child.size());

    Map<String, Object> union = new HashMap<>();
    child.forEach(reader -> union.put(reader.tag(), reader.objectValue()));
    assertEquals(2, union.size());
    assertFalse(union.containsKey(BASE_SERVICE));

    child.set(BASE_SERVICE, "restored"); // re-setting clears the tombstone
    assertEquals("restored", child.getObject(BASE_SERVICE));
    assertEquals(3, child.size());
    child.checkIntegrity();
  }

  @Test
  void getAndSetReturnsAParentsDenseValueAsThePrior() {
    TagMap child = TagMapType.DENSE.createFromParent(frozenParent());

    TagMap.Entry prior = child.getAndSet(COMPONENT, "grpc");
    assertNotNull(prior);
    assertEquals("web", prior.objectValue());
    assertEquals("grpc", child.getObject(COMPONENT));
  }

  @Test
  void putAllFromAReadThroughSourceCopiesTheUnion() {
    TagMap child = TagMapType.DENSE.createFromParent(frozenParent());
    child.set(DB_TYPE, "h2");

    TagMap dst = TagMapType.DENSE.create();
    dst.putAll(child);

    assertEquals(4, dst.size());
    assertEquals("billing", dst.getObject(BASE_SERVICE));
    assertEquals("h2", dst.getObject(DB_TYPE));
    assertEquals("alpha", dst.getObject(CUSTOM_A));
    dst.checkIntegrity();
  }

  @Test
  void aMultiLevelChainResolvesNearestFirst() {
    TagMap middle = TagMapType.DENSE.createFromParent(frozenParent());
    middle.set(COMPONENT, "middle");
    middle.remove(BASE_SERVICE);
    TagMap leaf = TagMapType.DENSE.createFromParent(middle.freeze());
    leaf.set(DB_TYPE, "leaf");

    assertEquals("middle", leaf.getObject(COMPONENT));
    assertNull(leaf.getObject(BASE_SERVICE));
    assertEquals("leaf", leaf.getObject(DB_TYPE));
    assertEquals(3, leaf.size());

    Map<String, Object> union = new HashMap<>();
    leaf.forEach(reader -> union.put(reader.tag(), reader.objectValue()));
    assertEquals(3, union.size());
    assertEquals("middle", union.get(COMPONENT));
    assertFalse(union.containsKey(BASE_SERVICE));
  }

  @Test
  void theCoresKnownValueReadMatchesGetEntry() {
    TagMap child = TagMapType.DENSE.createFromParent(frozenParent());
    child.set(HTTP_ROUTE, "/users/{id}"); // dense
    child.set(COMPONENT, "child"); // dense, shadows the parent's
    child.remove(BASE_SERVICE); // tombstones the parent's

    // local hit, no Entry built
    assertEquals("/users/{id}", child.internals().getKnownValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals("child", child.internals().getKnownValue(KnownTags.COMPONENT_ID));
    // read-through parent hit, then a tombstoned one and a plain miss
    TagMap reader = TagMapType.DENSE.createFromParent(frozenParent());
    assertEquals("web", reader.internals().getKnownValue(KnownTags.COMPONENT_ID));
    assertEquals("billing", reader.internals().getKnownValue(KnownTags.DD_BASE_SERVICE_ID));
    assertNull(child.internals().getKnownValue(KnownTags.DD_BASE_SERVICE_ID));
    assertNull(child.internals().getKnownValue(KnownTags.DB_TYPE_ID));
    // a known tag without a slot, held in the dense store all the same
    child.set(UNSLOTTED_KNOWN, "GET /users");
    assertEquals("GET /users", child.internals().getKnownValue(KnownTags.RESOURCE_NAME_ID));
  }
}

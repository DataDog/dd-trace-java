package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A TagMap's two stores -- an {@link IdStore} for known tags ({@link HashIdStore} unless dense mode
 * is on), a {@link HashStore} for custom tags, created lazily -- the name-or-id routing over them,
 * and the span's view ({@link TagMap#viewOf}).
 */
class TagStoresTest {
  private static TagMap frozenParent() {
    TagMap parent = TagMap.create();
    parent.set(KnownTags.HTTP_ROUTE_ID, "/inherited");
    parent.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    parent.set("inherited.custom", "c");
    return parent.freeze();
  }

  @Test
  void aMapWithNoCustomTagsHasNoCustomStore() {
    TagMap tags = TagMap.create();
    tags.set(KnownTags.HTTP_ROUTE_ID, "/a");
    tags.set(KnownTags.COMPONENT_NAME, "jdbc");
    assertNull(tags.nameStore);

    tags.set("my.tag", "x");
    assertNotNull(tags.nameStore);
    assertEquals(3, tags.size());
  }

  @Test
  void aChildBorrowsItsParentsCustomStoreUntilItWritesOne() {
    TagMap parent = frozenParent();
    TagMap child = TagMap.createFromParent(parent);
    assertSame(parent.nameStore, child.nameStore);
    assertTrue(child.nameStoreBorrowed);
    assertEquals("c", child.getString("inherited.custom"));

    child.set(KnownTags.HTTP_METHOD_ID, "GET"); // a known write keeps borrowing
    assertTrue(child.nameStoreBorrowed);
    assertFalse(child.remove("not.there")); // nothing to hide keeps borrowing
    assertTrue(child.nameStoreBorrowed);

    child.set("own.custom", "o");
    assertFalse(child.nameStoreBorrowed);
    assertNotSame(parent.nameStore, child.nameStore);
    assertEquals("c", child.getString("inherited.custom")); // still reads through
    assertEquals(5, child.size());
    assertEquals("c", parent.getString("inherited.custom"));
    assertNull(parent.getString("own.custom"));
  }

  @Test
  void removingABorrowedCustomTagHidesItLocally() {
    TagMap parent = frozenParent();
    TagMap child = TagMap.createFromParent(parent);

    assertEquals("c", child.getAndRemove("inherited.custom").stringValue());

    assertFalse(child.nameStoreBorrowed);
    assertNull(child.getString("inherited.custom"));
    assertEquals(2, child.size());
    assertEquals("c", parent.getString("inherited.custom"));
  }

  @Test
  void removingAnInheritedKnownTagTombstonesItWithoutCountingIt() {
    TagMap parent = frozenParent();
    TagMap child = TagMap.createFromParent(parent);

    assertEquals("/inherited", child.getAndRemove(KnownTags.HTTP_ROUTE_ID).stringValue());
    assertNull(child.getAndRemove(KnownTags.HTTP_ROUTE_ID)); // already removed

    assertNull(child.getEntry(KnownTags.HTTP_ROUTE_ID));
    assertEquals(2, child.size());
    assertFalse(child.containsKey(KnownTags.HTTP_ROUTE_NAME));
    for (TagMap.EntryReader entry : child) {
      assertNotEquals(KnownTags.HTTP_ROUTE_ID, entry.tagId());
    }
    IdStore ids = child.knownStore;
    assertEquals(0, ids.computeSize());
    assertNotEquals(0L, ids.tombstoneMask);
    child.checkIntegrity();

    // a re-set undoes the removal: no prior visible value
    assertNull(child.getAndSet(KnownTags.HTTP_ROUTE_NAME, "/again"));
    assertEquals("/again", child.getString(KnownTags.HTTP_ROUTE_NAME));
    assertEquals(3, child.size());
  }

  @Test
  void tombstonesAreSharedPerTag() {
    TagMap.Entry first = TagMap.Entry.tombstoneOf(KnownTags.HTTP_ROUTE_ID);
    assertSame(first, TagMap.Entry.tombstoneOf(KnownTags.HTTP_ROUTE_ID));
    assertTrue(first.tombstone);
    assertEquals(KnownTags.HTTP_ROUTE_NAME, first.tag());
  }

  @Test
  void aSpanLevelReadSkipsAParentHoldingOnlyTraceLevelTagsInItsSlot() {
    // runtime-id (trace level) and http.method (span level) are colored into the same slot
    assertEquals(IdStore.slotBit(KnownTags.RUNTIME_ID), IdStore.slotBit(KnownTags.HTTP_METHOD_ID));
    assertEquals(IdStore.TRACE_LEVEL, IdStore.levelOf(KnownTags.RUNTIME_ID));
    assertEquals(IdStore.SPAN_LEVEL, IdStore.levelOf(KnownTags.HTTP_METHOD_ID));

    HashIdStore traceLevel = new HashIdStore(null);
    traceLevel.set(KnownTags.RUNTIME_ID, "abc");
    HashIdStore span = new HashIdStore(traceLevel);
    span.set(KnownTags.HTTP_ROUTE_ID, "/a");

    long bit = IdStore.slotBit(KnownTags.HTTP_METHOD_ID);
    assertFalse(span.mayInherit(bit, IdStore.levelOf(KnownTags.HTTP_METHOD_ID)));
    assertTrue(span.mayInherit(bit, IdStore.levelOf(KnownTags.RUNTIME_ID)));
    assertFalse(traceLevel.mayHaveLocal(bit, IdStore.SPAN_LEVEL));
    assertNull(span.getEntryReader(KnownTags.HTTP_METHOD_ID));
    assertEquals("abc", span.getValue(KnownTags.RUNTIME_ID));
  }

  @Test
  void aClearSlotBitIsADefiniteMiss() {
    HashIdStore ids = new HashIdStore(null);
    assertEquals(0L, ids.occupancy);
    assertNull(ids.getEntryReader(KnownTags.HTTP_ROUTE_ID));

    ids.set(KnownTags.HTTP_ROUTE_ID, "/a");
    assertNotEquals(0L, ids.occupancy & IdStore.slotBit(KnownTags.HTTP_ROUTE_ID));
    assertEquals("/a", ids.getValue(KnownTags.HTTP_ROUTE_ID));

    // a child inherits its parent's slots, so a read-through miss can stop early
    HashIdStore child = new HashIdStore(ids);
    assertEquals(ids.occupancy, child.inheritedMask);
    assertEquals("/a", child.getValue(KnownTags.HTTP_ROUTE_ID));
  }

  @Test
  void anUnknownIdIsRejected() {
    HashIdStore ids = new HashIdStore(null);
    assertThrows(IllegalArgumentException.class, () -> ids.getEntryReader(0L));
    assertThrows(IllegalArgumentException.class, () -> ids.remove(0L));
  }

  @Test
  void theIdStoreSetsEveryValueShape() {
    HashIdStore ids = new HashIdStore(null);
    ids.set(KnownTags.COMPONENT_ID, (Object) new StringBuilder("jdbc"));
    ids.set(KnownTags.HTTP_ROUTE_ID, "/a");
    ids.set(KnownTags.DD_PROFILING_ENABLED_ID, true);
    ids.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    ids.set(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    ids.set(KnownTags.DB_POOL_NAME_ID, 1.5f);
    ids.set(KnownTags.DB_INSTANCE_ID, 2.25d);

    TagMap checked = TagMap.create();
    checked.set(KnownTags.COMPONENT_ID, ids.getEntryReader(KnownTags.COMPONENT_ID).objectValue());
    checked.set(KnownTags.HTTP_ROUTE_ID, "/a");
    checked.set(KnownTags.DD_PROFILING_ENABLED_ID, true);
    checked.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    checked.set(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    checked.set(KnownTags.DB_POOL_NAME_ID, 1.5f);
    checked.set(KnownTags.DB_INSTANCE_ID, 2.25d);

    Map<String, Object> actual = new HashMap<>();
    ids.fillMapLocal(actual);
    assertEquals(new HashMap<>(checked), actual);
    assertEquals(7, ids.size());
    assertEquals(7, ids.computeSize());
    assertTrue(ids.toInternalString().contains(KnownTags.HTTP_ROUTE_NAME));
  }

  @Test
  void putAllIntoAStoreWithTombstonesPutsOneAtATime() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.remove(KnownTags.HTTP_ROUTE_NAME); // a tombstone

    TagMap source = TagMap.create();
    source.set(KnownTags.HTTP_ROUTE_ID, "/from-source");
    source.set(KnownTags.HTTP_METHOD_ID, "GET");
    child.putAll(source);

    assertEquals("/from-source", child.getString(KnownTags.HTTP_ROUTE_NAME));
    assertEquals(4, child.size()); // route, method, status code, inherited custom
    child.checkIntegrity();
  }

  @Test
  void aCopyKeepsTombstonesAndBorrowing() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.remove(KnownTags.HTTP_ROUTE_NAME);

    TagMap copy = child.copy();
    assertNull(copy.getString(KnownTags.HTTP_ROUTE_NAME));
    assertTrue(copy.nameStoreBorrowed);
    assertEquals(child.size(), copy.size());

    copy.set(KnownTags.HTTP_ROUTE_NAME, "/copy");
    assertNull(child.getString(KnownTags.HTTP_ROUTE_NAME)); // independent
  }

  @Test
  void anIteratorWalksBothStoresAndTheirParents() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set(KnownTags.HTTP_METHOD_ID, "GET");
    child.set("own.custom", "o");
    child.remove("inherited.custom");

    Map<String, Object> seen = new HashMap<>();
    for (TagMap.EntryReader entry : child) {
      seen.put(entry.tag(), entry.objectValue());
    }
    Map<String, Object> expected = new HashMap<>();
    expected.put(KnownTags.HTTP_ROUTE_NAME, "/inherited");
    expected.put(KnownTags.HTTP_STATUS_CODE_NAME, 200);
    expected.put(KnownTags.HTTP_METHOD_NAME, "GET");
    expected.put("own.custom", "o");
    assertEquals(expected, seen);
    assertEquals(expected, new HashMap<>(child));
  }

  @Test
  void routingReadsKnownTagsByIdAndEverythingElseByName() {
    IdStore ids = IdStore.create(null);
    HashStore names = HashStore.create(null);
    // a direction-scoped OpenTelemetry name is resolved at the OpenTelemetry boundary, not here
    assertEquals(0L, KnownTagCodec.keyOf("server.address"));

    ids.set(KnownTags.HTTP_ROUTE_ID, "/a");
    names.set("my.tag", "custom");

    TagMap view = TagMap.viewOf(ids, names, false);
    assertEquals("/a", view.getEntry(KnownTags.HTTP_ROUTE_NAME).stringValue());
    assertEquals("custom", view.getEntry("my.tag").stringValue());
    assertNull(TagMap.viewOf(ids, null, false).getEntry("my.tag")); // no custom store yet
  }

  @Test
  void anOwnerReadsThroughItsParentsStores() {
    assertNull(TagMap.readThroughParent(null));
    assertThrows(IllegalStateException.class, () -> TagMap.readThroughParent(TagMap.create()));
    assertNull(TagMap.readThroughParent(TagMap.create().freeze())); // empty parent dropped

    TagMap parent = TagMap.readThroughParent(frozenParent());
    IdStore ids = IdStore.create(parent);
    HashStore borrowed = HashStore.inheritedFrom(parent);
    assertSame(parent.nameStore, borrowed);
    assertEquals("/inherited", ids.getValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals("c", borrowed.get("inherited.custom").stringValue());

    HashStore own = HashStore.create(borrowed);
    own.set("own", "o");
    assertEquals("c", own.get("inherited.custom").stringValue());
    assertNull(borrowed.get("own"));
  }

  @Test
  void theCustomStoreSetsEveryValueShape() {
    HashStore names = HashStore.create(null);
    names.set("o", (Object) "obj");
    names.set("s", "v");
    names.set("b", true);
    names.set("i", 1);
    names.set("l", 2L);
    names.set("f", 3f);
    names.set("d", 4d);
    assertNull(names.getAndPut(TagMap.Entry.create("p", "x")));
    assertEquals("x", names.put(TagMap.Entry.create("p", "y")).stringValue());

    assertEquals(true, names.get("b").objectValue());
    assertEquals(4d, names.get("d").objectValue());
    assertEquals(8, names.size());
  }

  @Test
  void aViewWritesThroughToItsOwnersStoresAndHandsBackANewCustomStore() {
    TagMap parent = TagMap.readThroughParent(frozenParent());
    IdStore ids = IdStore.create(parent);
    HashStore borrowed = HashStore.inheritedFrom(parent);
    TagMap view = TagMap.viewOf(ids, borrowed, true);
    assertSame(borrowed, view.customStore());
    assertTrue(view.isCustomStoreBorrowed());

    view.set(KnownTags.HTTP_METHOD_NAME, "GET");
    view.set("own", "o");
    assertEquals("GET", ids.getValue(KnownTags.HTTP_METHOD_ID));
    assertFalse(view.isCustomStoreBorrowed());
    assertNotSame(borrowed, view.customStore());
    assertEquals("o", view.customStore().get("own").stringValue());
  }

  @Test
  void clearDetachesBothStores() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("own", "o");
    child.clear();
    assertTrue(child.isEmpty());
    assertNull(child.nameStore);
    assertNull(child.getString("inherited.custom"));
    assertTrue(child.toInternalString().contains("nameStore:"));
  }

  /** Every known tag's real id, from the registry. */
  private static java.util.List<Long> knownIds() {
    java.util.List<Long> ids = new java.util.ArrayList<>();
    for (int serial = 1; serial < KnownTagCodec.serialLimit(); serial++) {
      long id = KnownTagCodec.keyOf(KnownTagCodec.nameOf(KnownTagCodec.makeTagId(serial)));
      if (id > 0) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * A three-level chain with enough tags in each store to fill bucket groups, checked against a
   * HashMap model through every read path: lookups, size, emptiness, iteration and forEach.
   */
  @Test
  void aDeepChainReadsLikeItsModel() {
    java.util.List<Long> ids = knownIds();
    Map<String, Object> model = new HashMap<>();

    TagMap grandparent = TagMap.create();
    for (int i = 0; i < ids.size(); i += 2) {
      grandparent.set(ids.get(i), "gp" + i);
      model.put(KnownTagCodec.nameOf(ids.get(i)), "gp" + i);
    }
    for (int i = 0; i < 40; i++) {
      grandparent.set("custom." + i, "gp" + i);
      model.put("custom." + i, "gp" + i);
    }
    grandparent.freeze();

    TagMap parent = TagMap.createFromParent(grandparent);
    for (int i = 1; i < ids.size(); i += 4) {
      parent.set(ids.get(i), "p" + i);
      model.put(KnownTagCodec.nameOf(ids.get(i)), "p" + i);
    }
    for (int i = 0; i < 40; i += 3) {
      parent.set("custom." + i, "p" + i);
      model.put("custom." + i, "p" + i);
    }
    parent.remove(KnownTagCodec.nameOf(ids.get(0))); // tombstone a grandparent known tag
    model.remove(KnownTagCodec.nameOf(ids.get(0)));
    parent.remove("custom.1");
    model.remove("custom.1");
    parent.freeze();

    TagMap child = TagMap.createFromParent(parent);
    for (int i = 0; i < ids.size(); i += 3) {
      child.set(ids.get(i), "c" + i);
      model.put(KnownTagCodec.nameOf(ids.get(i)), "c" + i);
    }
    for (int i = 2; i < ids.size(); i += 5) {
      String name = KnownTagCodec.nameOf(ids.get(i));
      child.remove(name);
      model.remove(name);
    }
    for (int i = 0; i < 40; i += 4) {
      child.set("custom." + i, "c" + i);
      model.put("custom." + i, "c" + i);
    }
    child.remove("custom.2");
    model.remove("custom.2");

    assertEquals(model.size(), child.size());
    assertFalse(child.isEmpty());
    assertFalse(child.isDefinitelyEmpty());
    assertTrue(child.estimateSize() >= model.size());
    assertEquals(model, new HashMap<>(child));
    for (Map.Entry<String, Object> entry : model.entrySet()) {
      assertEquals(entry.getValue(), child.getObject(entry.getKey()), entry.getKey());
    }

    Map<String, Object> viaConsumer = new HashMap<>();
    child.forEach(entry -> viaConsumer.put(entry.tag(), entry.objectValue()));
    assertEquals(model, viaConsumer);
    Map<String, Object> viaBi = new HashMap<>();
    child.forEach(viaBi, (m, entry) -> m.put(entry.tag(), entry.objectValue()));
    assertEquals(model, viaBi);
    Map<String, Object> viaTri = new HashMap<>();
    child.forEach(viaTri, "", (m, unused, entry) -> m.put(entry.tag(), entry.objectValue()));
    assertEquals(model, viaTri);
    Map<String, Object> filled = new HashMap<>();
    child.fillMap(filled);
    assertEquals(model, filled);

    TagMap flat = TagMap.create();
    flat.set("custom.0", "flat");
    flat.set(ids.get(0), "flat");
    flat.putAll(child); // a read-through source: copied as its visible union
    assertEquals(model, new HashMap<>(flat));
    flat.checkIntegrity();
  }

  @Test
  void aChildThatHidesEverythingInheritedIsEmpty() {
    TagMap parent = TagMap.create();
    parent.set(KnownTags.HTTP_ROUTE_ID, "/a");
    parent.set("c", "x");
    TagMap child = TagMap.createFromParent(parent.freeze());
    assertFalse(child.isEmpty());

    child.remove(KnownTags.HTTP_ROUTE_NAME);
    assertFalse(child.isEmpty());
    child.remove("c");
    assertTrue(child.isEmpty());
    assertEquals(0, child.size());
    assertTrue(child.isDefinitelyEmpty() == false); // the parent still holds local entries
  }

  @Test
  void removingALocalOverrideOfAnInheritedTagReturnsTheLocalValue() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set(KnownTags.HTTP_ROUTE_ID, "/local");
    child.set("inherited.custom", "local");

    assertEquals("/local", child.getAndRemove(KnownTags.HTTP_ROUTE_ID).stringValue());
    assertEquals("local", child.getAndRemove("inherited.custom").stringValue());
    assertNull(child.getString(KnownTags.HTTP_ROUTE_NAME));
    assertNull(child.getString("inherited.custom"));
  }

  @Test
  void putAllMergesIntoNonEmptyStores() {
    TagMap dest = TagMap.create();
    dest.set(KnownTags.HTTP_ROUTE_ID, "/dest");
    dest.set("d", "dest");
    TagMap source = TagMap.create();
    source.set(KnownTags.HTTP_METHOD_ID, "GET");
    source.set(KnownTags.HTTP_ROUTE_ID, "/source");
    source.set("s", "source");

    dest.putAll(source);

    assertEquals("/source", dest.getString(KnownTags.HTTP_ROUTE_NAME));
    assertEquals("GET", dest.getString(KnownTags.HTTP_METHOD_NAME));
    assertEquals("source", dest.getString("s"));
    assertEquals("dest", dest.getString("d"));
    assertEquals(4, dest.size());
    dest.checkIntegrity();
  }

  @Test
  void aCustomStoreRejectsAKnownTagsName() {
    HashStore names = new HashStore(null);
    assertThrows(AssertionError.class, () -> names.get(KnownTags.HTTP_ROUTE_NAME));
    assertThrows(AssertionError.class, () -> names.remove(KnownTags.HTTP_ROUTE_NAME));
  }
}

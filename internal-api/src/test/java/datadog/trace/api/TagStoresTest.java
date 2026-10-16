package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
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
 * A TagMap's two stores -- {@link HashIdStore} for known tags, {@link HashStore} for custom tags,
 * created lazily -- the {@link TagRouting} over them, and the span's TagMap ({@link
 * TagMap#forSpan}).
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
    assertNull(tags.names);

    tags.set("my.tag", "x");
    assertNotNull(tags.names);
    assertEquals(3, tags.size());
  }

  @Test
  void aChildBorrowsItsParentsCustomStoreUntilItWritesOne() {
    TagMap parent = frozenParent();
    TagMap child = TagMap.createFromParent(parent);
    assertSame(parent.names, child.names);
    assertTrue(child.namesBorrowed);
    assertEquals("c", child.getString("inherited.custom"));

    child.set(KnownTags.HTTP_METHOD_ID, "GET"); // a known write keeps borrowing
    assertTrue(child.namesBorrowed);
    assertFalse(child.remove("not.there")); // nothing to hide keeps borrowing
    assertTrue(child.namesBorrowed);

    child.set("own.custom", "o");
    assertFalse(child.namesBorrowed);
    assertNotSame(parent.names, child.names);
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

    assertFalse(child.namesBorrowed);
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
    HashIdStore ids = (HashIdStore) child.ids;
    assertEquals(0, ids.count);
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
  void aClearSlotBitIsADefiniteMiss() {
    HashIdStore ids = new HashIdStore(null);
    assertEquals(0L, ids.occupancy);
    assertNull(ids.get(KnownTags.HTTP_ROUTE_ID));

    ids.set(KnownTags.HTTP_ROUTE_ID, "/a");
    assertNotEquals(0L, ids.occupancy & IdStore.slotBit(KnownTags.HTTP_ROUTE_ID));
    assertEquals("/a", ids.getKnownValue(KnownTags.HTTP_ROUTE_ID));

    // a child inherits its parent's slots, so a read-through miss can stop early
    HashIdStore child = new HashIdStore(ids);
    assertEquals(ids.occupancy, child.inheritedMask);
    assertEquals("/a", child.getKnownValue(KnownTags.HTTP_ROUTE_ID));
  }

  @Test
  void anUnknownIdIsRejected() {
    HashIdStore ids = new HashIdStore(null);
    assertThrows(IllegalArgumentException.class, () -> ids.get(0L));
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
    checked.set(KnownTags.COMPONENT_ID, ids.get(KnownTags.COMPONENT_ID).objectValue());
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
    assertTrue(copy.namesBorrowed);
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
  void routingSendsKnownTagsByIdAndEverythingElseByName() {
    TagMap tags = TagMap.create();
    long route = TagRouting.keyOf(KnownTags.HTTP_ROUTE_NAME);
    long alias = TagRouting.keyOf("server.address");
    assertEquals(KnownTags.HTTP_ROUTE_ID, route);
    assertEquals(0L, TagRouting.keyOf("my.tag"));
    assertTrue(alias < 0);

    TagRouting.set(tags, route, KnownTags.HTTP_ROUTE_NAME, (Object) "/a");
    TagRouting.set(tags, 0L, "my.tag", (Object) "custom");
    TagRouting.set(tags, alias, "server.address", (Object) "as-written");

    assertEquals("/a", TagRouting.get(tags, route, KnownTags.HTTP_ROUTE_NAME).stringValue());
    assertEquals("/a", TagRouting.getKnownValue(tags, KnownTags.HTTP_ROUTE_ID));
    assertEquals("custom", TagRouting.getCustom(tags, "my.tag").stringValue());
    assertEquals("as-written", TagRouting.getCustom(tags, "server.address").stringValue());

    assertNotNull(TagRouting.remove(tags, route, KnownTags.HTTP_ROUTE_NAME));
    assertNotNull(TagRouting.removeCustom(tags, "my.tag"));
    assertNull(TagRouting.getById(tags, KnownTags.HTTP_ROUTE_ID));
    assertNull(TagRouting.removeById(tags, KnownTags.HTTP_ROUTE_ID));
    assertNull(TagRouting.remove(tags, 0L, "my.tag"));
  }

  @Test
  void routingSetsEveryValueShape() {
    TagMap tags = TagMap.create();
    TagRouting.setKnown(tags, KnownTags.COMPONENT_ID, (Object) "jdbc");
    TagRouting.setKnown(tags, KnownTags.HTTP_ROUTE_ID, "/a");
    TagRouting.setKnown(tags, KnownTags.DD_PROFILING_ENABLED_ID, true);
    TagRouting.setKnown(tags, KnownTags.HTTP_STATUS_CODE_ID, 200);
    TagRouting.setKnown(tags, KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    TagRouting.setKnown(tags, KnownTags.DB_POOL_NAME_ID, 1.5f);
    TagRouting.setKnown(tags, KnownTags.DB_INSTANCE_ID, 2.25d);
    TagRouting.set(tags, KnownTags.HTTP_METHOD_ID, "x", "GET");
    TagRouting.set(tags, KnownTags.DD_PROFILING_ENABLED_ID, "x", false);
    TagRouting.set(tags, KnownTags.HTTP_STATUS_CODE_ID, "x", 201);
    TagRouting.set(tags, KnownTags.HTTP_RESEND_COUNT_ID, "x", 3L);
    TagRouting.set(tags, KnownTags.DB_POOL_NAME_ID, "x", 2.5f);
    TagRouting.set(tags, KnownTags.DB_INSTANCE_ID, "x", 3.25d);
    TagRouting.set(tags, 0L, "s", "v");
    TagRouting.set(tags, 0L, "b", true);
    TagRouting.set(tags, 0L, "i", 1);
    TagRouting.set(tags, 0L, "l", 2L);
    TagRouting.set(tags, 0L, "f", 3f);
    TagRouting.set(tags, 0L, "d", 4d);
    TagRouting.setCustom(tags, "o", "obj");

    assertEquals(201, tags.getObject(KnownTags.HTTP_STATUS_CODE_NAME));
    assertEquals(false, tags.getObject(KnownTags.DD_PROFILING_ENABLED_NAME));
    assertEquals(3.25d, tags.getObject(KnownTags.DB_INSTANCE_NAME));
    assertEquals(4d, tags.getObject("d"));
    assertEquals("obj", tags.getObject("o"));
    assertEquals(15, tags.size());
  }

  @Test
  void routingPutsEntriesByTheirKind() {
    TagMap tags = TagMap.create();
    assertNull(TagRouting.put(tags, TagMap.Entry.create(KnownTags.HTTP_ROUTE_ID, "/a")));
    assertNull(TagRouting.getAndPut(tags, TagMap.Entry.create("my.tag", "x")));
    assertEquals(
        "/a",
        TagRouting.getAndPut(tags, TagMap.Entry.create(KnownTags.HTTP_ROUTE_ID, "/b"))
            .stringValue());
    assertEquals("x", TagRouting.put(tags, TagMap.Entry.create("my.tag", "y")).stringValue());
  }

  @Test
  void aSpansTagsReadAnAliasByItsDirection() {
    TagMap inbound = TagMap.forSpan(null, direction(DIRECTION_INBOUND, false));
    TagMap outbound = TagMap.forSpan(null, direction(DIRECTION_OUTBOUND, false));
    for (TagMap tags : new TagMap[] {inbound, outbound}) {
      tags.set(KnownTags.HTTP_HOSTNAME_ID, "inbound.example");
      tags.set(KnownTags.PEER_HOSTNAME_ID, "outbound.example");
    }
    TagMap plain = TagMap.create();
    plain.set(KnownTags.PEER_HOSTNAME_ID, "outbound.example");

    assertEquals("inbound.example", inbound.getString("server.address"));
    assertEquals("outbound.example", outbound.getString("server.address"));
    assertNull(plain.getString("server.address")); // a TagMap of its own reads names as written
    assertEquals("inbound.example", outbound.getString(KnownTags.HTTP_HOSTNAME_NAME));
  }

  @Test
  void aSpansTagsReadAnEmittedPendingAliasOverItsTag() {
    TagMap pending = TagMap.forSpan(null, direction(DIRECTION_OUTBOUND, true));
    TagMap noPending = TagMap.forSpan(null, direction(DIRECTION_OUTBOUND, false));
    TagMap unknown = TagMap.forSpan(null, direction(DIRECTION_UNKNOWN, true));
    for (TagMap tags : new TagMap[] {pending, noPending, unknown}) {
      tags.set(KnownTags.PEER_HOSTNAME_ID, "older");
      TagRouting.setCustom(tags, "server.address", "pending");
    }

    assertEquals("pending", pending.getString(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals("pending", pending.getString("server.address"));
    assertEquals("older", noPending.getString(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals("older", unknown.getString(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals("pending", unknown.getString("server.address"));
    assertNull(noPending.getString("client.port")); // no tag in this direction
  }

  @Test
  void forSpanMirrorsCreateFromParent() {
    assertThrows(
        IllegalStateException.class,
        () -> TagMap.forSpan(TagMap.create(), direction(DIRECTION_UNKNOWN, false)));
    assertNull(TagMap.forSpan(TagMap.create().freeze(), null).parent); // empty parent dropped

    TagMap span = TagMap.forSpan(frozenParent(), direction(DIRECTION_UNKNOWN, false));
    assertEquals("/inherited", span.getString(KnownTags.HTTP_ROUTE_NAME));
    assertEquals(3, span.size());
  }

  @Test
  void clearDetachesBothStores() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("own", "o");
    child.clear();
    assertTrue(child.isEmpty());
    assertNull(child.names);
    assertNull(child.getString("inherited.custom"));
    assertTrue(child.toInternalString().contains("names:"));
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

  private static TagMap.DirectionSource direction(int direction, boolean pendingAliases) {
    return new TagMap.DirectionSource() {
      @Override
      public int tagDirection() {
        return direction;
      }

      @Override
      public boolean hasPendingAliases() {
        return pendingAliases;
      }
    };
  }
}

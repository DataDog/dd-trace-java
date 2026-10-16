package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

/**
 * The tag stores a span owns directly ({@link HashStore}, its {@link IdStore} adapter), the {@link
 * TagRouting} over them, and the {@link TagMap} view a span hands its internal readers.
 */
class TagStoresTest {
  @Test
  void trustedKnownSetsStoreWhatTheCheckedSetsStore() {
    Object component = new StringBuilder("jdbc"); // not a String: exercises the Object overload
    TagMap checked = TagMap.create();
    checked.set(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    checked.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    checked.set(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    checked.set(KnownTags.DD_PROFILING_ENABLED_ID, true);
    checked.set(KnownTags.COMPONENT_ID, component);
    checked.set(KnownTags.DB_POOL_NAME_ID, 1.5f);
    checked.set(KnownTags.DB_INSTANCE_ID, 2.25d);

    HashStore hash = new HashStore();
    hash.setKnown(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    hash.setKnown(KnownTags.HTTP_STATUS_CODE_ID, 200);
    hash.setKnown(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    hash.setKnown(KnownTags.DD_PROFILING_ENABLED_ID, true);
    hash.setKnown(KnownTags.COMPONENT_ID, component);
    hash.setKnown(KnownTags.DB_POOL_NAME_ID, 1.5f);
    hash.setKnown(KnownTags.DB_INSTANCE_ID, 2.25d);

    assertEquals(new HashMap<>(checked), new HashMap<>(TagMap.viewOf(null, hash, null)));
    assertEquals(KnownTags.HTTP_ROUTE_ID, hash.getById(KnownTags.HTTP_ROUTE_ID).tagId());
    assertEquals(1.5f, hash.getById(KnownTags.DB_POOL_NAME_ID).floatValue());
  }

  @Test
  void theIdStoreAdapterReadsAndWritesTheHashStoresKnownTags() {
    HashStore hash = new HashStore();
    IdStore ids = hash.idStore();
    ids.set(KnownTags.HTTP_ROUTE_ID, (Object) "/a");
    ids.set(KnownTags.COMPONENT_ID, "jdbc");
    ids.set(KnownTags.DD_PROFILING_ENABLED_ID, true);
    ids.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    ids.set(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    ids.set(KnownTags.DB_POOL_NAME_ID, 1.5f);
    ids.set(KnownTags.DB_INSTANCE_ID, 2.25d);

    assertEquals("/a", hash.getKnownValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals("jdbc", ids.get(KnownTags.COMPONENT_ID).stringValue());
    assertEquals(true, ids.getKnownValue(KnownTags.DD_PROFILING_ENABLED_ID));
    assertEquals(200, ids.getKnownValue(KnownTags.HTTP_STATUS_CODE_ID));
    assertEquals(2L, ids.getKnownValue(KnownTags.HTTP_RESEND_COUNT_ID));
    assertEquals(1.5f, ids.getKnownValue(KnownTags.DB_POOL_NAME_ID));
    assertEquals(2.25d, ids.getKnownValue(KnownTags.DB_INSTANCE_ID));

    TagMap.Entry route = TagMap.Entry.create(KnownTags.HTTP_ROUTE_ID, "/b");
    assertEquals("/a", ids.put(route).stringValue());
    assertEquals(
        "/b", ids.getAndPut(TagMap.Entry.create(KnownTags.HTTP_ROUTE_ID, "/c")).stringValue());
    assertEquals("/c", ids.remove(KnownTags.HTTP_ROUTE_ID).stringValue());
    assertNull(hash.getById(KnownTags.HTTP_ROUTE_ID));
    assertThrows(IllegalArgumentException.class, () -> ids.get(0L));
  }

  @Test
  void customTagsAreKeptByName() {
    HashStore hash = new HashStore();
    hash.set("a", (Object) "x");
    hash.set("b", "y");
    hash.set("c", true);
    hash.set("d", 1);
    hash.set("e", 2L);
    hash.set("f", 3f);
    hash.set("g", 4d);

    assertEquals("x", hash.get("a").objectValue());
    assertEquals("y", hash.get("b").stringValue());
    assertTrue(hash.get("c").booleanValue());
    assertEquals(1, hash.get("d").intValue());
    assertEquals(2L, hash.get("e").longValue());
    assertEquals(3f, hash.get("f").floatValue());
    assertEquals(4d, hash.get("g").doubleValue());
    assertEquals("x", hash.remove("a").objectValue());
    assertNull(hash.get("a"));
  }

  @Test
  void aNameStoreRejectsAKnownTagsName() {
    HashStore hash = new HashStore();
    assertThrows(AssertionError.class, () -> hash.get(KnownTags.HTTP_ROUTE_NAME));
    assertThrows(AssertionError.class, () -> hash.remove(KnownTags.HTTP_ROUTE_NAME));
  }

  @Test
  void routingSendsKnownTagsByIdAndEverythingElseByName() {
    HashStore hash = new HashStore();
    IdStore ids = TagRouting.idStoreFor(hash);

    long route = TagRouting.keyOf(KnownTags.HTTP_ROUTE_NAME);
    long custom = TagRouting.keyOf("my.tag");
    long alias = TagRouting.keyOf("server.address");
    assertEquals(KnownTags.HTTP_ROUTE_ID, route);
    assertEquals(0L, custom);
    assertTrue(alias < 0);

    TagRouting.set(ids, hash, route, KnownTags.HTTP_ROUTE_NAME, (Object) "/a");
    TagRouting.set(ids, hash, custom, "my.tag", (Object) "custom");
    TagRouting.set(ids, hash, alias, "server.address", (Object) "as-written");

    assertEquals("/a", TagRouting.get(ids, hash, route, KnownTags.HTTP_ROUTE_NAME).stringValue());
    assertEquals("/a", TagRouting.getKnownValue(ids, hash, KnownTags.HTTP_ROUTE_ID));
    assertEquals("custom", hash.get("my.tag").stringValue());
    assertEquals("as-written", hash.get("server.address").stringValue()); // an alias is custom

    assertNotNull(TagRouting.remove(ids, hash, route, KnownTags.HTTP_ROUTE_NAME));
    assertNotNull(TagRouting.remove(ids, hash, custom, "my.tag"));
    assertNull(TagRouting.getById(ids, hash, KnownTags.HTTP_ROUTE_ID));
    assertNull(hash.get("my.tag"));
  }

  @Test
  void routingSetsEveryValueShapeByKey() {
    HashStore hash = new HashStore();
    IdStore ids = TagRouting.idStoreFor(hash);
    TagRouting.set(ids, hash, KnownTags.COMPONENT_ID, "component", "jdbc");
    TagRouting.set(ids, hash, KnownTags.DD_PROFILING_ENABLED_ID, "x", true);
    TagRouting.set(ids, hash, KnownTags.HTTP_STATUS_CODE_ID, "x", 200);
    TagRouting.set(ids, hash, KnownTags.HTTP_RESEND_COUNT_ID, "x", 2L);
    TagRouting.set(ids, hash, KnownTags.DB_POOL_NAME_ID, "x", 1.5f);
    TagRouting.set(ids, hash, KnownTags.DB_INSTANCE_ID, "x", 2.25d);
    TagRouting.set(ids, hash, 0L, "s", "v");
    TagRouting.set(ids, hash, 0L, "b", true);
    TagRouting.set(ids, hash, 0L, "i", 1);
    TagRouting.set(ids, hash, 0L, "l", 2L);
    TagRouting.set(ids, hash, 0L, "f", 3f);
    TagRouting.set(ids, hash, 0L, "d", 4d);

    TagMap expected = TagMap.create();
    expected.set(KnownTags.COMPONENT_ID, "jdbc");
    expected.set(KnownTags.DD_PROFILING_ENABLED_ID, true);
    expected.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    expected.set(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    expected.set(KnownTags.DB_POOL_NAME_ID, 1.5f);
    expected.set(KnownTags.DB_INSTANCE_ID, 2.25d);
    expected.set("s", "v");
    expected.set("b", true);
    expected.set("i", 1);
    expected.set("l", 2L);
    expected.set("f", 3f);
    expected.set("d", 4d);
    assertEquals(new HashMap<>(expected), new HashMap<>(TagMap.viewOf(ids, hash, null)));
  }

  @Test
  void routingPutsEntriesByTheirKind() {
    HashStore hash = new HashStore();
    IdStore ids = TagRouting.idStoreFor(hash);
    assertNull(TagRouting.put(ids, hash, TagMap.Entry.create(KnownTags.HTTP_ROUTE_ID, "/a")));
    assertNull(TagRouting.getAndPut(ids, hash, TagMap.Entry.create("my.tag", "x")));
    assertEquals(
        "/a",
        TagRouting.getAndPut(ids, hash, TagMap.Entry.create(KnownTags.HTTP_ROUTE_ID, "/b"))
            .stringValue());
    assertEquals("x", TagRouting.put(ids, hash, TagMap.Entry.create("my.tag", "y")).stringValue());
  }

  @Test
  void putAllCopiesTheVisibleTags() {
    TagMap parent = TagMap.create();
    parent.set("inherited", "p");
    TagMap source = TagMap.createFromParent(parent.freeze());
    source.set(KnownTags.HTTP_ROUTE_ID, "/a");

    HashStore hash = new HashStore();
    TagRouting.putAll(TagRouting.idStoreFor(hash), hash, source);
    assertEquals("p", hash.get("inherited").stringValue());
    assertEquals("/a", hash.getKnownValue(KnownTags.HTTP_ROUTE_ID));
  }

  @Test
  void aViewReadsAnAliasByTheSpansDirection() {
    HashStore hash = new HashStore();
    hash.setKnown(KnownTags.HTTP_HOSTNAME_ID, "inbound.example");
    hash.setKnown(KnownTags.PEER_HOSTNAME_ID, "outbound.example");

    TagMap inbound = TagMap.viewOf(null, hash, direction(DIRECTION_INBOUND, false));
    TagMap outbound = TagMap.viewOf(null, hash, direction(DIRECTION_OUTBOUND, false));
    TagMap plain = TagMap.viewOf(null, hash, null);

    assertEquals("inbound.example", inbound.getString("server.address"));
    assertEquals("outbound.example", outbound.getString("server.address"));
    assertNull(plain.getString("server.address")); // a TagMap of its own reads names as written
    assertEquals("inbound.example", outbound.getString(KnownTags.HTTP_HOSTNAME_NAME));
  }

  @Test
  void aViewReadsAPendingAliasOverItsTag() {
    HashStore hash = new HashStore();
    hash.setKnown(KnownTags.PEER_HOSTNAME_ID, "older");
    hash.set("server.address", "pending");

    TagMap pending = TagMap.viewOf(null, hash, direction(DIRECTION_OUTBOUND, true));
    TagMap noPending = TagMap.viewOf(null, hash, direction(DIRECTION_OUTBOUND, false));
    TagMap unknown = TagMap.viewOf(null, hash, direction(DIRECTION_UNKNOWN, true));

    assertEquals("pending", pending.getString(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals("pending", pending.getString("server.address"));
    assertEquals("older", noPending.getString(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals("older", unknown.getString(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals("pending", unknown.getString("server.address"));
    assertNull(noPending.getString("client.port")); // no tag in this direction
  }

  @Test
  void aViewIsLiveOverTheStores() {
    HashStore hash = new HashStore();
    TagMap view = TagMap.viewOf(null, hash, direction(DIRECTION_OUTBOUND, false));
    view.set(KnownTags.HTTP_ROUTE_NAME, "/a");
    view.set("my.tag", "x");
    assertEquals("/a", hash.getKnownValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals("x", hash.get("my.tag").stringValue());

    TagMap copy = view.copy();
    view.remove("my.tag");
    assertEquals("x", copy.getString("my.tag")); // a copy is a TagMap of its own
    assertFalse(view.containsKey("my.tag"));
    assertSame(hash, view.hashStore());
  }

  @Test
  void readThroughStoreMirrorsCreateFromParent() {
    assertNull(TagMap.readThroughStore(null));
    assertThrows(IllegalStateException.class, () -> TagMap.readThroughStore(TagMap.create()));
    assertNull(TagMap.readThroughStore(TagMap.create().freeze()));

    TagMap parent = TagMap.create();
    parent.set(KnownTags.HTTP_ROUTE_ID, "/inherited");
    parent.freeze();
    HashStore parentStore = TagMap.readThroughStore(parent);
    assertSame(parent.hashStore(), parentStore);

    HashStore child = new HashStore(parentStore);
    assertEquals("/inherited", child.getKnownValue(KnownTags.HTTP_ROUTE_ID));
    child.removeById(KnownTags.HTTP_ROUTE_ID); // tombstones the inherited tag
    assertNull(child.getKnownValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals("/inherited", parent.getString(KnownTags.HTTP_ROUTE_NAME));
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

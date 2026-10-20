package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.TagMap.EntryReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@link IdStore} contract, run against both stores in the normal test JVM: the dense store is
 * behind a flag that is off by default, so the shared {@link TagMap} suites only reach the hash
 * store. Each store is built from a parent of its own kind, as the factories do.
 */
class IdStoreContractTest {

  static Stream<Arguments> stores() {
    return Stream.of(
        arguments(named("HashIdStore", (Function<IdStore, IdStore>) HashIdStore::new)),
        arguments(named("DenseIdStore", (Function<IdStore, IdStore>) DenseIdStore::new)));
  }

  /** Every known tag id, in declaration order. */
  private static List<Long> knownIds() throws IllegalAccessException {
    List<Long> ids = new ArrayList<>();
    for (java.lang.reflect.Field field : KnownTags.class.getFields()) {
      if (field.getType() == long.class) {
        long id = field.getLong(null);
        if (KnownTagCodec.isKnown(id) && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  private static Map<String, Object> visible(IdStore store) {
    Map<String, Object> seen = new HashMap<>();
    store.forEach(entry -> assertNull(seen.put(entry.tag(), entry.objectValue()), entry.tag()));
    return seen;
  }

  @ParameterizedTest
  @MethodSource("stores")
  void typedSettersRoundTripThroughTheReader(Function<IdStore, IdStore> newStore) {
    IdStore store = newStore.apply(null);
    store.set(KnownTags.HTTP_ROUTE_ID, (Object) "/users");
    store.set(KnownTags.HTTP_METHOD_ID, (CharSequence) "GET");
    store.set(KnownTags.ERROR_ID, true);
    store.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    store.set(KnownTags.PEER_PORT_ID, 5432L);
    store.set(KnownTags.DD_AGENT_PSR_ID, 0.5f);
    store.set(KnownTags.DB_TYPE_ID, (Object) "postgres");
    store.set(KnownTags.COMPONENT_ID, (CharSequence) "jdbc");
    store.set(KnownTags.SPAN_KIND_ID, (Object) "client");
    store.set(KnownTags.DB_POOL_NAME_ID, (Object) "pool");
    store.set(KnownTags.HTTP_URL_ID, (Object) "http://x");
    store.set(KnownTags.DB_INSTANCE_ID, 2.5d);

    assertEquals("/users", store.getValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals("GET", store.getValue(KnownTags.HTTP_METHOD_ID));

    EntryReader flag = store.getEntryReader(KnownTags.ERROR_ID);
    assertTrue(flag.booleanValue());
    assertEquals(EntryReader.BOOLEAN, flag.type());
    assertFalse(flag.isNumber());

    EntryReader status = store.getEntryReader(KnownTags.HTTP_STATUS_CODE_ID);
    assertEquals(200, status.intValue());
    assertEquals(200L, status.longValue());
    assertTrue(status.is(EntryReader.INT));
    assertTrue(status.isNumericPrimitive());
    assertFalse(status.isObject());
    assertEquals(KnownTags.HTTP_STATUS_CODE_ID, status.tagId());
    assertEquals("http.status_code", status.tag());

    assertEquals(5432L, store.getEntryReader(KnownTags.PEER_PORT_ID).longValue());
    assertEquals(0.5f, store.getEntryReader(KnownTags.DD_AGENT_PSR_ID).floatValue());
    assertEquals(2.5d, store.getEntryReader(KnownTags.DB_INSTANCE_ID).doubleValue());

    EntryReader route = store.getEntryReader(KnownTags.HTTP_ROUTE_ID);
    assertEquals("/users", route.stringValue());
    assertTrue(route.isObject());
    assertEquals("/users", route.entry().objectValue());
    assertEquals("http.route", route.mapEntry().getKey());

    assertEquals(12, store.size());
    store.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void putAndGetAndPutReturnThePriorValue(Function<IdStore, IdStore> newStore) {
    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/inherited");
    IdStore store = newStore.apply(parent);

    assertNull(store.put(TagMap.Entry.newAnyEntry(KnownTags.HTTP_METHOD_ID, "GET")));
    EntryReader prior = store.put(TagMap.Entry.newAnyEntry(KnownTags.HTTP_METHOD_ID, "POST"));
    assertEquals("GET", prior.objectValue());

    // getAndPut reports the prior visible value, inherited included
    EntryReader inherited =
        store.getAndPut(TagMap.Entry.newAnyEntry(KnownTags.HTTP_ROUTE_ID, "/local"));
    assertEquals("/inherited", inherited.objectValue());
    assertEquals("/local", store.getValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals(
        "/local",
        store.getAndPut(TagMap.Entry.newAnyEntry(KnownTags.HTTP_ROUTE_ID, "/again")).objectValue());
    assertNull(store.getAndPut(TagMap.Entry.newAnyEntry(KnownTags.COMPONENT_ID, "okhttp")));
    store.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void removingAnInheritedTagHidesItUntilItIsSetAgain(Function<IdStore, IdStore> newStore) {
    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/inherited");
    parent.set(KnownTags.COMPONENT_ID, "okhttp");
    IdStore store = newStore.apply(parent);
    store.set(KnownTags.HTTP_METHOD_ID, "GET");
    assertTrue(store.hasParent());
    assertEquals(3, store.size());

    assertEquals("/inherited", store.remove(KnownTags.HTTP_ROUTE_ID).objectValue());
    assertNull(store.getValue(KnownTags.HTTP_ROUTE_ID));
    assertNull(store.remove(KnownTags.HTTP_ROUTE_ID), "already hidden");
    assertEquals(2, store.size());
    assertEquals("/inherited", parent.getValue(KnownTags.HTTP_ROUTE_ID), "parent untouched");

    // a local value that also hides an inherited one is removed with its tombstone in place
    store.set(KnownTags.COMPONENT_ID, "local");
    assertEquals("local", store.remove(KnownTags.COMPONENT_ID).objectValue());
    assertNull(store.getValue(KnownTags.COMPONENT_ID));

    assertEquals("GET", store.remove(KnownTags.HTTP_METHOD_ID).objectValue());
    assertNull(store.remove(KnownTags.HTTP_METHOD_ID), "absent");
    assertTrue(store.isEmpty());
    assertTrue(store.isDefinitelyEmpty() || store.estimateSize() >= 0);

    // setting a hidden tag again revives it
    store.set(KnownTags.HTTP_ROUTE_ID, "/revived");
    assertEquals("/revived", store.getValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals(1, store.size());
    assertFalse(store.isEmpty());
    store.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void sizeAndEmptinessCountWhatIsVisible(Function<IdStore, IdStore> newStore) {
    IdStore empty = newStore.apply(null);
    assertTrue(empty.isEmpty());
    assertTrue(empty.isDefinitelyEmpty());
    assertEquals(0, empty.estimateSize());
    assertFalse(empty.hasParent());

    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/a");
    parent.set(KnownTags.HTTP_METHOD_ID, "GET");
    IdStore child = newStore.apply(parent);
    assertFalse(child.isEmpty(), "inherits visible tags");
    assertFalse(child.isDefinitelyEmpty());

    child.set(KnownTags.HTTP_METHOD_ID, "POST"); // shadows
    child.set(KnownTags.COMPONENT_ID, "okhttp");
    assertEquals(3, child.size());
    assertTrue(child.estimateSize() >= 3);

    child.remove(KnownTags.HTTP_ROUTE_ID);
    child.remove(KnownTags.HTTP_METHOD_ID);
    child.remove(KnownTags.COMPONENT_ID);
    assertTrue(child.isEmpty(), "every inherited tag hidden");
    assertEquals(0, child.size());
  }

  @ParameterizedTest
  @MethodSource("stores")
  void everyIterationSeesEachVisibleTagOnce(Function<IdStore, IdStore> newStore) {
    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/a");
    parent.set(KnownTags.HTTP_METHOD_ID, "GET");
    parent.set(KnownTags.COMPONENT_ID, "okhttp");
    IdStore store = newStore.apply(parent);
    store.set(KnownTags.HTTP_METHOD_ID, "POST"); // shadows the parent's
    store.set(KnownTags.SPAN_KIND_ID, "client");
    store.remove(KnownTags.COMPONENT_ID); // hides the parent's

    Map<String, Object> expected = new HashMap<>();
    expected.put("http.route", "/a");
    expected.put("http.method", "POST");
    expected.put("span.kind", "client");
    assertEquals(expected, visible(store));

    Map<String, Object> viaContext = new HashMap<>();
    store.forEach(viaContext, (map, entry) -> map.put(entry.tag(), entry.objectValue()));
    assertEquals(expected, viaContext);

    Map<String, Object> viaTwoContexts = new HashMap<>();
    store.forEach(
        viaTwoContexts, "-", (map, suffix, entry) -> map.put(entry.tag(), entry.objectValue()));
    assertEquals(expected, viaTwoContexts);

    Map<String, Object> viaCursor = new HashMap<>();
    IdStore.Cursor cursor = store.cursor();
    for (EntryReader entry = cursor.next(); entry != null; entry = cursor.next()) {
      assertNull(viaCursor.put(entry.tag(), entry.objectValue()), entry.tag());
    }
    assertEquals(expected, viaCursor);
  }

  @ParameterizedTest
  @MethodSource("stores")
  void aCopyIsIndependent(Function<IdStore, IdStore> newStore) {
    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/a");
    IdStore store = newStore.apply(parent);
    store.set(KnownTags.HTTP_METHOD_ID, "GET");
    store.remove(KnownTags.HTTP_ROUTE_ID);

    IdStore copy = store.copy();
    copy.set(KnownTags.HTTP_METHOD_ID, "POST");
    copy.set(KnownTags.HTTP_ROUTE_ID, "/copy");

    assertEquals("GET", store.getValue(KnownTags.HTTP_METHOD_ID));
    assertNull(store.getValue(KnownTags.HTTP_ROUTE_ID), "the original keeps its tombstone");
    assertEquals("POST", copy.getValue(KnownTags.HTTP_METHOD_ID));
    assertEquals("/copy", copy.getValue(KnownTags.HTTP_ROUTE_ID));
    copy.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void putAllLocalMergesAnotherStoresTags(Function<IdStore, IdStore> newStore) {
    IdStore source = newStore.apply(null);
    source.set(KnownTags.HTTP_ROUTE_ID, "/a");
    source.set(KnownTags.HTTP_METHOD_ID, "GET");

    IdStore empty = newStore.apply(null);
    empty.putAllLocal(source); // nothing here yet
    assertEquals(visible(source), visible(empty));

    IdStore merged = newStore.apply(null);
    merged.set(KnownTags.HTTP_METHOD_ID, "POST");
    merged.set(KnownTags.COMPONENT_ID, "okhttp");
    merged.putAllLocal(source);
    assertEquals("GET", merged.getValue(KnownTags.HTTP_METHOD_ID), "the source wins");
    assertEquals("okhttp", merged.getValue(KnownTags.COMPONENT_ID));
    assertEquals(3, merged.size());

    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/inherited");
    IdStore tombstoned = newStore.apply(parent);
    tombstoned.remove(KnownTags.HTTP_ROUTE_ID);
    tombstoned.putAllLocal(source); // replaces the tombstone
    assertEquals("/a", tombstoned.getValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals(2, tombstoned.size());

    newStore.apply(null).putAllLocal(newStore.apply(null)); // an empty source is a no-op
    merged.checkIntegrity();
    tombstoned.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void fillsCopyTheLocalTags(Function<IdStore, IdStore> newStore) {
    IdStore store = newStore.apply(null);
    store.set(KnownTags.HTTP_ROUTE_ID, "/a");
    store.set(KnownTags.HTTP_STATUS_CODE_ID, 200);

    Map<String, Object> map = new HashMap<>();
    store.fillMapLocal(map);
    assertEquals("/a", map.get("http.route"));
    assertEquals(200, map.get("http.status_code"));

    Map<String, String> strings = new HashMap<>();
    store.fillStringMapLocal(strings);
    assertEquals("200", strings.get("http.status_code"));
  }

  @ParameterizedTest
  @MethodSource("stores")
  void growsPastItsFirstCapacity(Function<IdStore, IdStore> newStore) throws Exception {
    IdStore store = newStore.apply(null);
    List<Long> ids = knownIds();
    int n = Math.min(40, ids.size());
    for (int i = 0; i < n; ++i) {
      store.set(ids.get(i), "v" + i);
    }
    for (int i = 0; i < n; ++i) {
      assertEquals("v" + i, store.getValue(ids.get(i)));
    }
    assertEquals(n, store.size());
    assertTrue(store.computeSize() > 0);
    assertNotNull(store.toInternalString());
    store.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void putAllLocalMergesOverlappingStoresWithCollidingTags(Function<IdStore, IdStore> newStore)
      throws Exception {
    // enough tags that buckets hold groups of colliding entries on both sides of the merge
    List<Long> ids = knownIds();
    IdStore target = newStore.apply(null);
    IdStore source = newStore.apply(null);
    for (int i = 0; i < 30; ++i) {
      target.set(ids.get(i), "target" + i);
    }
    for (int i = 20; i < 50; ++i) {
      source.set(ids.get(i), "source" + i);
    }
    target.putAllLocal(source);
    assertEquals(50, target.size());
    for (int i = 0; i < 50; ++i) {
      assertEquals(i < 20 ? "target" + i : "source" + i, target.getValue(ids.get(i)));
    }
    target.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void getAndPutRevivesARemovedInheritedTag(Function<IdStore, IdStore> newStore) {
    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/inherited");
    IdStore store = newStore.apply(parent);
    store.remove(KnownTags.HTTP_ROUTE_ID);

    // hidden, so there is no visible prior value
    assertNull(store.getAndPut(TagMap.Entry.newAnyEntry(KnownTags.HTTP_ROUTE_ID, "/revived")));
    assertEquals("/revived", store.getValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals(1, store.size());
    store.checkIntegrity();
  }

  @ParameterizedTest
  @MethodSource("stores")
  void readsGoThroughEveryAncestor(Function<IdStore, IdStore> newStore) {
    IdStore grandparent = newStore.apply(null);
    grandparent.set(KnownTags.HTTP_ROUTE_ID, "/grandparent");
    grandparent.set(KnownTags.COMPONENT_ID, "okhttp");
    IdStore parent = newStore.apply(grandparent);
    parent.set(KnownTags.HTTP_METHOD_ID, "GET");
    parent.remove(KnownTags.COMPONENT_ID);
    IdStore store = newStore.apply(parent);

    assertEquals("/grandparent", store.getValue(KnownTags.HTTP_ROUTE_ID));
    assertEquals("GET", store.getValue(KnownTags.HTTP_METHOD_ID));
    assertNull(store.getValue(KnownTags.COMPONENT_ID), "hidden by the parent");
    assertEquals(2, store.size());
    Map<String, Object> expected = new HashMap<>();
    expected.put("http.route", "/grandparent");
    expected.put("http.method", "GET");
    assertEquals(expected, visible(store));
  }

  @ParameterizedTest
  @MethodSource("stores")
  void aReaderWithNoEntryIsANoOp(Function<IdStore, IdStore> newStore) {
    IdStore store = newStore.apply(null);
    store.set(KnownTags.HTTP_ROUTE_ID, "/a");
    NoEntryReader empty = new NoEntryReader("http.route", KnownTags.HTTP_ROUTE_ID);

    assertNull(store.put(empty));
    assertNull(store.getAndPut(empty));
    assertEquals("/a", store.getValue(KnownTags.HTTP_ROUTE_ID), "left as it was");
    assertEquals(1, store.size());
  }

  @ParameterizedTest
  @MethodSource("stores")
  void getEntryReturnsAnEntryTheCallerMayKeep(Function<IdStore, IdStore> newStore) {
    IdStore parent = newStore.apply(null);
    parent.set(KnownTags.HTTP_ROUTE_ID, "/inherited");
    IdStore store = newStore.apply(parent);
    store.set(KnownTags.HTTP_METHOD_ID, "GET");

    TagMap.Entry method = store.getEntry(KnownTags.HTTP_METHOD_ID);
    TagMap.Entry route = store.getEntry(KnownTags.HTTP_ROUTE_ID);
    EntryReader methodReader = store.getEntryReader(KnownTags.HTTP_METHOD_ID);
    EntryReader routeReader = store.getEntryReader(KnownTags.HTTP_ROUTE_ID);

    // kept side by side, each still holds its own tag
    assertEquals("GET", method.objectValue());
    assertEquals(KnownTags.HTTP_METHOD_ID, method.tagId());
    assertEquals("/inherited", route.objectValue());
    assertEquals("GET", methodReader.objectValue());
    assertEquals("/inherited", routeReader.objectValue());
    assertNull(store.getEntry(KnownTags.COMPONENT_ID));
    assertThrows(
        IllegalArgumentException.class, () -> store.getEntry(KnownTagCodec.makeTagId(9999)));
  }

  @ParameterizedTest
  @MethodSource("stores")
  void anUnknownIdIsRejected(Function<IdStore, IdStore> newStore) {
    IdStore store = newStore.apply(null);
    long unknown = KnownTagCodec.makeTagId(9999);
    assertThrows(IllegalArgumentException.class, () -> store.getValue(unknown));
    assertThrows(IllegalArgumentException.class, () -> store.remove(unknown));
  }

  /** An EntryReader with no entry and no value, as a caller outside the tracer may pass. */
  static final class NoEntryReader implements TagMap.EntryReader {
    private final String tag;
    private final long tagId;

    NoEntryReader(String tag, long tagId) {
      this.tag = tag;
      this.tagId = tagId;
    }

    @Override
    public String tag() {
      return tag;
    }

    @Override
    public long tagId() {
      return tagId;
    }

    @Override
    public byte type() {
      return TagMap.EntryReader.OBJECT;
    }

    @Override
    public boolean is(byte type) {
      return false;
    }

    @Override
    public boolean isNumericPrimitive() {
      return false;
    }

    @Override
    public boolean isNumber() {
      return false;
    }

    @Override
    public boolean isObject() {
      return true;
    }

    @Override
    public Object objectValue() {
      return null;
    }

    @Override
    public String stringValue() {
      return null;
    }

    @Override
    public boolean booleanValue() {
      return false;
    }

    @Override
    public int intValue() {
      return 0;
    }

    @Override
    public long longValue() {
      return 0;
    }

    @Override
    public float floatValue() {
      return 0;
    }

    @Override
    public double doubleValue() {
      return 0;
    }

    @Override
    public java.util.Map.Entry<String, Object> mapEntry() {
      return null;
    }

    @Override
    public TagMap.Entry entry() {
      return null;
    }
  }
}

package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** {@link TagMap#set(long, Object)} and its overloads: setting a known tag by its id. */
class TagMapSetByIdTest {
  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void storesUnderTheCanonicalName(TagMapType type) {
    TagMap map = type.create();

    map.set(KnownTags.PEER_HOSTNAME_ID, "db.internal");

    assertEquals("db.internal", map.getObject(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals(KnownTags.PEER_HOSTNAME_ID, map.getEntry(KnownTags.PEER_HOSTNAME_NAME).tagId());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void anOpenTelemetryRenameIsStoredUnderTheDatadogName(TagMapType type) {
    TagMap byId = type.create();
    TagMap byOtelName = type.create();

    byId.set(KnownTags.HTTP_METHOD_ID, "GET");
    byOtelName.set(KnownTags.HTTP_METHOD_OTEL_NAME, "GET");

    assertEquals(new HashMap<>(byOtelName), new HashMap<>(byId));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void matchesTheNameKeyedSettersForEveryValueType(TagMapType type) {
    TagMap byId = type.create();
    byId.set(KnownTags.HTTP_STATUS_CODE_ID, 5432);
    byId.set(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    byId.set(KnownTags.PEER_HOSTNAME_ID, (CharSequence) "db.internal");
    byId.set(KnownTags.DB_INSTANCE_ID, (Object) "orders");
    byId.set(KnownTags.DD_PROFILING_ENABLED_ID, true);
    byId.set(KnownTags.DB_USER_ID, 1.5f);
    byId.set(KnownTags.DB_POOL_NAME_ID, 2.5d);

    TagMap byName = type.create();
    byName.set(KnownTags.HTTP_STATUS_CODE_NAME, 5432);
    byName.set(KnownTags.HTTP_RESEND_COUNT_NAME, 2L);
    byName.set(KnownTags.PEER_HOSTNAME_NAME, (CharSequence) "db.internal");
    byName.set(KnownTags.DB_INSTANCE_NAME, (Object) "orders");
    byName.set(KnownTags.DD_PROFILING_ENABLED_NAME, true);
    byName.set(KnownTags.DB_USER_NAME, 1.5f);
    byName.set(KnownTags.DB_POOL_NAME, 2.5d);

    assertEquals(new HashMap<>(byName), new HashMap<>(byId));
    assertEquals(5432, byId.getEntry(KnownTags.HTTP_STATUS_CODE_NAME).intValue());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void getsAndRemovesById(TagMapType type) {
    TagMap map = type.create();
    map.set(KnownTags.HTTP_METHOD_NAME, "GET");

    assertEquals("GET", map.getEntry(KnownTags.HTTP_METHOD_ID).stringValue());
    assertEquals("GET", map.getAndRemove(KnownTags.HTTP_METHOD_ID).stringValue());
    assertNull(map.getEntry(KnownTags.HTTP_METHOD_ID));
    assertNull(map.getAndRemove(KnownTags.HTTP_METHOD_ID));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void anUnknownIdIsRejected(TagMapType type) {
    TagMap map = type.create();

    assertThrows(IllegalArgumentException.class, () -> map.set(0L, "value"));
    assertThrows(IllegalArgumentException.class, () -> map.set(KnownTagCodec.makeTagId(9999), 1));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void theCoresTrustedSetsStoreWhatTheCheckedSetsStore(TagMapType type) {
    TagMap checked = type.create();
    checked.set(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    checked.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    checked.set(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    checked.set(KnownTags.DD_PROFILING_ENABLED_ID, true);

    TagMap trusted = type.create();
    trusted.internals().setKnown(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    trusted.internals().setKnown(KnownTags.HTTP_STATUS_CODE_ID, 200);
    trusted.internals().setKnown(KnownTags.HTTP_RESEND_COUNT_ID, 2L);
    trusted.internals().setKnown(KnownTags.DD_PROFILING_ENABLED_ID, true);

    assertEquals(new HashMap<>(checked), new HashMap<>(trusted));
    assertEquals(KnownTags.HTTP_ROUTE_ID, trusted.getEntry(KnownTags.HTTP_ROUTE_NAME).tagId());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void theCoresKnownValueReadMatchesGetEntry(TagMapType type) {
    TagMap parent = type.create();
    parent.set(KnownTags.HTTP_ROUTE_ID, "/parent");
    parent.set(KnownTags.HTTP_METHOD_ID, "GET");
    parent.set(KnownTags.PEER_HOSTNAME_ID, "db.internal");
    TagMap child = type.createFromParent(parent.freeze());
    child.set(KnownTags.HTTP_STATUS_CODE_ID, 200);
    child.set(KnownTags.HTTP_ROUTE_ID, "/child");
    child.remove(KnownTags.PEER_HOSTNAME_NAME);

    // local hit, boxed
    assertEquals(200, child.internals().getKnownValue(KnownTags.HTTP_STATUS_CODE_ID));
    // local shadows the parent
    assertEquals("/child", child.internals().getKnownValue(KnownTags.HTTP_ROUTE_ID));
    // read-through parent hit
    assertEquals("GET", child.internals().getKnownValue(KnownTags.HTTP_METHOD_ID));
    // tombstoned parent value and plain miss
    assertNull(child.internals().getKnownValue(KnownTags.PEER_HOSTNAME_ID));
    assertNull(child.internals().getKnownValue(KnownTags.DB_INSTANCE_ID));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void theCoresTrustedSetsCoverTheRemainingValueShapes(TagMapType type) {
    Object component = new StringBuilder("jdbc"); // not a String: exercises the Object overload
    TagMap checked = type.create();
    checked.set(KnownTags.COMPONENT_ID, component);
    checked.set(KnownTags.DB_POOL_NAME_ID, 1.5f);
    checked.set(KnownTags.DB_INSTANCE_ID, 2.25d);

    TagMap trusted = type.create();
    trusted.internals().setKnown(KnownTags.COMPONENT_ID, component);
    trusted.internals().setKnown(KnownTags.DB_POOL_NAME_ID, 1.5f);
    trusted.internals().setKnown(KnownTags.DB_INSTANCE_ID, 2.25d);

    assertEquals(new HashMap<>(checked), new HashMap<>(trusted));
    assertEquals(1.5f, trusted.getEntry(KnownTags.DB_POOL_NAME_ID).floatValue());
    assertEquals(2.25d, trusted.getEntry(KnownTags.DB_INSTANCE_ID).doubleValue());
  }
}

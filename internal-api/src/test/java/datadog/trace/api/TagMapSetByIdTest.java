package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** {@link TagMap#set(long, Object)} and its overloads: setting a known tag by its id. */
class TagMapSetByIdTest {
  @Test
  void storesUnderTheCanonicalName() {
    TagMap map = TagMap.create();

    map.set(KnownTags.PEER_HOSTNAME, "db.internal");

    assertEquals("db.internal", map.getObject(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals(KnownTags.PEER_HOSTNAME, map.getEntry(KnownTags.PEER_HOSTNAME_NAME).tagId());
  }

  @Test
  void anOpenTelemetryRenameIsStoredUnderTheDatadogName() {
    TagMap byId = TagMap.create();
    TagMap byOtelName = TagMap.create();

    byId.set(KnownTags.HTTP_METHOD, "GET");
    byOtelName.set(KnownTags.HTTP_METHOD_OTEL_NAME, "GET");

    assertEquals(new HashMap<>(byOtelName), new HashMap<>(byId));
  }

  @Test
  void matchesTheNameKeyedSettersForEveryValueType() {
    TagMap byId = TagMap.create();
    byId.set(KnownTags.HTTP_STATUS_CODE, 5432);
    byId.set(KnownTags.HTTP_RESEND_COUNT, 2L);
    byId.set(KnownTags.PEER_HOSTNAME, (CharSequence) "db.internal");
    byId.set(KnownTags.DB_INSTANCE, (Object) "orders");
    byId.set(KnownTags.DD_PROFILING_ENABLED, true);
    byId.set(KnownTags.DB_USER, 1.5f);
    byId.set(KnownTags.DB_POOL_NAME, 2.5d);

    TagMap byName = TagMap.create();
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

  @Test
  void getsAndRemovesById() {
    TagMap map = TagMap.create();
    map.set(KnownTags.HTTP_METHOD_NAME, "GET");

    assertEquals("GET", map.getEntry(KnownTags.HTTP_METHOD).stringValue());
    assertEquals("GET", map.getAndRemove(KnownTags.HTTP_METHOD).stringValue());
    assertNull(map.getEntry(KnownTags.HTTP_METHOD));
    assertNull(map.getAndRemove(KnownTags.HTTP_METHOD));
  }

  @Test
  void anUnknownIdIsRejected() {
    TagMap map = TagMap.create();

    assertThrows(IllegalArgumentException.class, () -> map.set(0L, "value"));
    assertThrows(IllegalArgumentException.class, () -> map.set(KnownTagCodec.makeTagId(9999), 1));
  }
}

package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMap.Entry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the tag-id keyed {@code set}/{@code getEntry} surface on {@link TagMap} and {@link
 * TagMap.Ledger}.
 *
 * <p>In this (buckets-only) phase a tag-id keyed write builds a tag-id-bearing {@link Entry} and
 * stores it in the regular hash buckets. The entry carries its full identity (globalSerial /
 * fieldPos / nameHash) and resolves its name lazily via {@link KnownTags}, so it remains findable
 * by string name once a {@link KnownTags.Resolver} is registered.
 */
public class TagMapTagIdTest {
  // Test tags: name -> (globalSerial, fieldPos). nameHash is derived from Entry._hash(name) so the
  // tag-id-bearing entry lands in the same hash bucket a string-keyed entry would.
  static final String HTTP_METHOD = "http.request.method";
  static final String HTTP_STATUS = "http.response.status_code";
  static final String DB_SYSTEM = "db.system";

  static final long HTTP_METHOD_ID = tagId(1, 2, HTTP_METHOD);
  static final long HTTP_STATUS_ID = tagId(2, 5, HTTP_STATUS);
  static final long DB_SYSTEM_ID = tagId(3, 0, DB_SYSTEM);

  static long tagId(int globalSerial, int fieldPos, String name) {
    return KnownTags.tagId(globalSerial, fieldPos, name);
  }

  @Test
  public void tagId_roundTripsThroughExtractors() {
    long id = KnownTags.tagId(7, 13, "some.tag.name");
    assertEquals(7, KnownTags.globalSerial(id));
    assertEquals(13, KnownTags.fieldPos(id));
    assertEquals(Entry._hash("some.tag.name"), KnownTags.nameHash(id));
  }

  @BeforeEach
  public void registerResolver() {
    Map<Long, String> nameById = new HashMap<>();
    Map<String, Long> idByName = new HashMap<>();
    for (long id : new long[] {HTTP_METHOD_ID, HTTP_STATUS_ID, DB_SYSTEM_ID}) {
      // resolve name from the tag's own definition above
      String name =
          id == HTTP_METHOD_ID ? HTTP_METHOD : id == HTTP_STATUS_ID ? HTTP_STATUS : DB_SYSTEM;
      nameById.put(id, name);
      idByName.put(name, id);
    }
    KnownTags.register(
        new KnownTags.Resolver() {
          @Override
          public String nameOf(long tagId) {
            return nameById.get(tagId);
          }

          @Override
          public long keyOf(String name) {
            Long id = idByName.get(name);
            return id == null ? 0L : id;
          }

          @Override
          public int slotCount() {
            return 6; // max stored fieldPos (HTTP_STATUS=5) + 1
          }
        });
  }

  @AfterEach
  public void clearResolver() {
    KnownTags.register(null);
  }

  @Test
  public void setById_findableByIdAndName() {
    TagMap map = TagMap.create();
    map.set(HTTP_METHOD_ID, "GET");

    // findable by tag id
    Entry byId = map.getEntry(HTTP_METHOD_ID);
    assertNotNull(byId);
    assertEquals("GET", byId.stringValue());

    // findable by the resolved string name (read-path unification)
    Entry byName = map.getEntry(HTTP_METHOD);
    assertSame(byId, byName);
    assertEquals("GET", map.get(HTTP_METHOD));
  }

  @Test
  public void setById_preservesIdentityOnEntry() {
    TagMap map = TagMap.create();
    map.set(HTTP_STATUS_ID, 404);

    Entry entry = map.getEntry(HTTP_STATUS_ID);
    assertNotNull(entry);
    // globalSerial survives on the stored entry
    assertEquals(2, KnownTags.globalSerial(entry.tagId));
    assertEquals(5, KnownTags.fieldPos(entry.tagId));
    // name resolves lazily from the id
    assertEquals(HTTP_STATUS, entry.tag());
  }

  @Test
  public void setById_typedValues() {
    TagMap map = TagMap.create();
    map.set(HTTP_STATUS_ID, 500);

    Entry entry = map.getEntry(HTTP_STATUS_ID);
    assertTrue(entry.is(Entry.INT));
    assertEquals(500, entry.intValue());
    assertEquals(500, map.getInt(HTTP_STATUS));
  }

  @Test
  public void setById_overwriteSameTag() {
    TagMap map = TagMap.create();
    map.set(HTTP_METHOD_ID, "GET");
    map.set(HTTP_METHOD_ID, "POST");

    assertEquals("POST", map.get(HTTP_METHOD));
    assertEquals(1, map.size());
  }

  @Test
  public void setByName_findableById() {
    TagMap map = TagMap.create();
    // string write of a known tag is still findable through the id read path (resolves to the
    // same name and hash bucket)
    map.set(DB_SYSTEM, "postgresql");

    Entry byId = map.getEntry(DB_SYSTEM_ID);
    assertNotNull(byId);
    assertEquals("postgresql", byId.stringValue());
  }

  @Test
  public void getEntryById_missingReturnsNull() {
    TagMap map = TagMap.create();
    assertNull(map.getEntry(HTTP_METHOD_ID));
  }

  @Test
  public void ledger_setById_buildsMap() {
    TagMap map =
        TagMap.ledger()
            .set(HTTP_METHOD_ID, "GET")
            .set(HTTP_STATUS_ID, 204)
            .set(DB_SYSTEM_ID, "mysql")
            .build();

    assertEquals("GET", map.get(HTTP_METHOD));
    assertEquals(204, map.getInt(HTTP_STATUS));
    assertEquals("mysql", map.get(DB_SYSTEM));

    // tag id survives the ledger -> build path
    Entry status = map.getEntry(HTTP_STATUS_ID);
    assertEquals(2, KnownTags.globalSerial(status.tagId));
    assertEquals(204, status.intValue());
  }

  @Test
  public void ledger_mixedIdAndName() {
    TagMap map = TagMap.ledger().set(HTTP_METHOD_ID, "PUT").set(DB_SYSTEM, "redis").build();

    assertEquals("PUT", map.get(HTTP_METHOD));
    assertEquals("redis", map.get(DB_SYSTEM));
    assertEquals(2, map.size());
  }

  @Test
  public void removeById_clearsAndReportsSize() {
    TagMap map = TagMap.create();
    map.set(HTTP_METHOD_ID, "GET");
    assertEquals(1, map.size());

    assertTrue(map.remove(HTTP_METHOD_ID));
    assertNull(map.getEntry(HTTP_METHOD_ID));
    assertNull(map.getEntry(HTTP_METHOD));
    assertEquals(0, map.size());
    assertFalse(map.remove(HTTP_METHOD_ID)); // already gone
  }

  @Test
  public void getAndRemoveById_returnsPrior() {
    TagMap map = TagMap.create();
    map.set(HTTP_STATUS_ID, 404);

    Entry prior = map.getAndRemove(HTTP_STATUS_ID);
    assertNotNull(prior);
    assertEquals(404, prior.intValue());
    assertNull(map.getEntry(HTTP_STATUS_ID));
  }

  @Test
  public void removeById_removesStringSetTag() {
    // set by name, removed by id (id resolves to the same tag, slot or bucket)
    TagMap map = TagMap.create();
    map.set(DB_SYSTEM, "postgresql");

    assertTrue(map.remove(DB_SYSTEM_ID));
    assertNull(map.get(DB_SYSTEM));
  }

  @Test
  public void ledger_removeById() {
    TagMap map =
        TagMap.ledger()
            .set(HTTP_METHOD_ID, "GET")
            .set(DB_SYSTEM_ID, "mysql")
            .remove(DB_SYSTEM_ID)
            .build();

    assertEquals("GET", map.get(HTTP_METHOD));
    assertNull(map.get(DB_SYSTEM));
    assertEquals(1, map.size());
  }
}

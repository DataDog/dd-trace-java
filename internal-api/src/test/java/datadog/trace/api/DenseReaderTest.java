package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DenseIdStore.DenseReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DenseReaderTest {

  @Test
  void exposesAnIntValue() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_STATUS_CODE_ID, 200);

    assertEquals(200, reader.intValue());
    assertEquals(200L, reader.longValue());
    assertEquals(TagMap.EntryReader.INT, reader.type());
    assertTrue(reader.isNumber());
    assertTrue(reader.isNumericPrimitive());
    assertFalse(reader.isObject());
    assertTrue(reader.is(TagMap.EntryReader.INT));
  }

  @Test
  void exposesALongValue() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_STATUS_CODE_ID, 123456789L);

    assertEquals(123456789L, reader.longValue());
    assertEquals(TagMap.EntryReader.LONG, reader.type());
    assertTrue(reader.isNumericPrimitive());
  }

  @Test
  void exposesABooleanValue() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_STATUS_CODE_ID, true);

    assertTrue(reader.booleanValue());
    assertEquals(TagMap.EntryReader.BOOLEAN, reader.type());
    assertFalse(reader.isNumber());
    assertFalse(reader.isObject());
  }

  @Test
  void exposesFloatAndDoubleValues() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_STATUS_CODE_ID, 1.5f);
    assertEquals(1.5f, reader.floatValue());
    assertEquals(TagMap.EntryReader.FLOAT, reader.type());

    reader.set(KnownTags.HTTP_STATUS_CODE_ID, 3.14);
    assertEquals(3.14, reader.doubleValue());
    assertEquals(TagMap.EntryReader.DOUBLE, reader.type());
  }

  @Test
  void exposesAStringAsAnObject() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_METHOD_ID, "GET");

    assertEquals("GET", reader.stringValue());
    assertEquals("GET", reader.objectValue());
    assertEquals(TagMap.EntryReader.OBJECT, reader.type());
    assertTrue(reader.isObject());
    assertFalse(reader.isNumber());
  }

  @Test
  void namesTheTagFromItsId() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_METHOD_ID, "GET");

    assertEquals(KnownTags.HTTP_METHOD_ID, reader.tagId());
    assertEquals(KnownTags.HTTP_METHOD_NAME, reader.tag());
    assertEquals(KnownTags.HTTP_METHOD_NAME, reader.tag()); // the resolved name is reused
  }

  @Test
  void aNewTagReplacesTheResolvedName() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_METHOD_ID, "GET");
    assertEquals(KnownTags.HTTP_METHOD_NAME, reader.tag());

    reader.set(KnownTags.HTTP_ROUTE_ID, "/users");
    assertEquals(KnownTags.HTTP_ROUTE_ID, reader.tagId());
    assertEquals(KnownTags.HTTP_ROUTE_NAME, reader.tag());
  }

  @Test
  void buildsAnEntryForTheTag() {
    DenseReader reader = new DenseReader();
    reader.set(KnownTags.HTTP_METHOD_ID, "GET");

    TagMap.Entry entry = reader.entry();
    assertEquals(KnownTags.HTTP_METHOD_ID, entry.tagId());
    assertEquals(KnownTags.HTTP_METHOD_NAME, entry.tag());
    assertEquals("GET", entry.objectValue());

    Map.Entry<String, Object> mapEntry = reader.mapEntry();
    assertEquals(KnownTags.HTTP_METHOD_NAME, mapEntry.getKey());
    assertEquals("GET", mapEntry.getValue());
  }
}

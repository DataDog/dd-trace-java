package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntryReadingHelperTest {

  @Test
  void setTagValueExposesIntValue() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("my.tag", 42);

    assertEquals("my.tag", helper.tag());
    assertEquals(42, helper.intValue());
    assertEquals(42L, helper.longValue());
    assertEquals(TagMap.EntryReader.INT, helper.type());
    assertTrue(helper.isNumber());
    assertTrue(helper.isNumericPrimitive());
    assertFalse(helper.isObject());
    assertTrue(helper.is(TagMap.EntryReader.INT));
  }

  @Test
  void setTagValueExposesLongValue() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("id", 123456789L);

    assertEquals(123456789L, helper.longValue());
    assertEquals(TagMap.EntryReader.LONG, helper.type());
    assertTrue(helper.isNumber());
    assertTrue(helper.isNumericPrimitive());
  }

  @Test
  void setTagValueExposesBooleanValue() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("flag", true);

    assertTrue(helper.booleanValue());
    assertEquals(TagMap.EntryReader.BOOLEAN, helper.type());
    assertFalse(helper.isNumber());
    assertFalse(helper.isObject());
  }

  @Test
  void setTagValueExposesFloatValue() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("rate", 1.5f);

    assertEquals(1.5f, helper.floatValue());
    assertEquals(TagMap.EntryReader.FLOAT, helper.type());
    assertTrue(helper.isNumericPrimitive());
  }

  @Test
  void setTagValueExposesDoubleValue() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("pi", 3.14);

    assertEquals(3.14, helper.doubleValue());
    assertEquals(TagMap.EntryReader.DOUBLE, helper.type());
    assertTrue(helper.isNumericPrimitive());
  }

  @Test
  void setTagValueExposesStringAsObject() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("name", "foo");

    assertEquals("foo", helper.stringValue());
    assertEquals("foo", helper.objectValue());
    assertEquals(TagMap.EntryReader.OBJECT, helper.type());
    assertTrue(helper.isObject());
    assertFalse(helper.isNumber());
    assertFalse(helper.isNumericPrimitive());
  }

  @Test
  void setTagValueCreatesEntry() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("tag", "value");

    TagMap.Entry entry = helper.entry();
    assertNotNull(entry);
    assertEquals("tag", entry.tag());
    assertEquals("value", entry.objectValue());
  }

  @Test
  void mapEntryFallsBackToNewEntryWhenSetWithTagValue() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set("tag", "value");

    Map.Entry<String, Object> mapEntry = helper.mapEntry();
    assertEquals("tag", mapEntry.getKey());
    assertEquals("value", mapEntry.getValue());
  }

  @Test
  void setMapEntryExposesReadMethods() {
    EntryReadingHelper helper = new EntryReadingHelper();
    Map.Entry<String, Object> original = new AbstractMap.SimpleEntry<>("key", "hello");
    helper.set(original);

    assertEquals("key", helper.tag());
    assertEquals("hello", helper.stringValue());
    assertEquals("hello", helper.objectValue());
    assertEquals(TagMap.EntryReader.OBJECT, helper.type());
    assertTrue(helper.isObject());
    assertFalse(helper.isNumber());
  }

  @Test
  void mapEntryReturnsSameInstanceWhenSetWithMapEntry() {
    EntryReadingHelper helper = new EntryReadingHelper();
    Map.Entry<String, Object> original = new AbstractMap.SimpleEntry<>("key", "value");
    helper.set(original);

    assertSame(original, helper.mapEntry());
  }

  @Test
  void setMapEntryCreatesEntry() {
    EntryReadingHelper helper = new EntryReadingHelper();
    helper.set(new AbstractMap.SimpleEntry<>("k", 99));

    TagMap.Entry entry = helper.entry();
    assertNotNull(entry);
    assertEquals("k", entry.tag());
    assertEquals(99, entry.objectValue());
  }
}

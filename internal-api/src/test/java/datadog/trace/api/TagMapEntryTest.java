package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.Test;

public class TagMapEntryTest {
  @Test
  public void objectEntry() {
    TagMap.Entry entry = TagMap.Entry.newObjectEntry("foo", "bar");
    assertKey("foo", entry);
    assertValue("bar", entry);

    assertEquals("bar", entry.stringValue());

    assertTrue(entry.isObject());
  }

  @Test
  public void anyEntry_object() {
    TagMap.Entry entry = TagMap.Entry.newAnyEntry("foo", "bar");

    assertKey("foo", entry);
    assertValue("bar", entry);

    assertTrue(entry.isObject());

    assertKey("foo", entry);
    assertValue("bar", entry);
  }

  @Test
  public void booleanEntry() {
    TagMap.Entry entry = TagMap.Entry.newBooleanEntry("foo", true);

    assertKey("foo", entry);
    assertValue(true, entry);

    assertFalse(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.BOOLEAN));
  }

  @Test
  public void booleanEntry_boxed() {
    TagMap.Entry entry = TagMap.Entry.newBooleanEntry("foo", Boolean.valueOf(true));

    assertKey("foo", entry);
    assertValue(true, entry);

    assertFalse(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.BOOLEAN));
  }

  @Test
  public void anyEntry_boolean() {
    TagMap.Entry entry = TagMap.Entry.newBooleanEntry("foo", Boolean.valueOf(true));

    assertKey("foo", entry);
    assertValue(true, entry);

    assertFalse(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.BOOLEAN));

    assertValue(true, entry);
  }

  @Test
  public void intEntry() {
    TagMap.Entry entry = TagMap.Entry.newIntEntry("foo", 20);

    assertKey("foo", entry);
    assertValue(20, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.INT));
  }

  @Test
  public void intEntry_boxed() {
    TagMap.Entry entry = TagMap.Entry.newIntEntry("foo", Integer.valueOf(20));

    assertKey("foo", entry);
    assertValue(20, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.INT));
  }

  @Test
  public void anyEntry_int() {
    TagMap.Entry entry = TagMap.Entry.newAnyEntry("foo", Integer.valueOf(20));

    assertKey("foo", entry);
    assertValue(20, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.INT));

    assertValue(20, entry);
  }

  @Test
  public void longEntry() {
    TagMap.Entry entry = TagMap.Entry.newLongEntry("foo", 1_048_576L);

    assertKey("foo", entry);
    assertValue(1_048_576L, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.LONG));
  }

  @Test
  public void longEntry_boxed() {
    TagMap.Entry entry = TagMap.Entry.newLongEntry("foo", Long.valueOf(1_048_576L));

    assertKey("foo", entry);
    assertValue(1_048_576L, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.LONG));
  }

  @Test
  public void anyEntry_long() {
    TagMap.Entry entry = TagMap.Entry.newAnyEntry("foo", Long.valueOf(1_048_576L));

    assertKey("foo", entry);
    assertValue(1_048_576L, entry);

    // type checks force any resolution
    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.LONG));

    // check value again after resolution
    assertValue(1_048_576L, entry);
  }

  @Test
  public void doubleEntry() {
    TagMap.Entry entry = TagMap.Entry.newDoubleEntry("foo", Math.PI);

    assertKey("foo", entry);
    assertValue(Math.PI, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.DOUBLE));
  }

  @Test
  public void doubleEntry_boxed() {
    TagMap.Entry entry = TagMap.Entry.newDoubleEntry("foo", Double.valueOf(Math.PI));

    assertKey("foo", entry);
    assertValue(Math.PI, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.DOUBLE));
  }

  @Test
  public void anyEntry_double() {
    TagMap.Entry entry = TagMap.Entry.newAnyEntry("foo", Double.valueOf(Math.PI));

    assertKey("foo", entry);
    assertValue(Math.PI, entry);

    // type checks force any resolution
    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.DOUBLE));

    // check value again after resolution
    assertValue(Math.PI, entry);
  }

  @Test
  public void floatEntry() {
    TagMap.Entry entry = TagMap.Entry.newFloatEntry("foo", 2.718281828f);

    assertKey("foo", entry);
    assertValue(2.718281828f, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.FLOAT));
  }

  @Test
  public void floatEntry_boxed() {
    TagMap.Entry entry = TagMap.Entry.newFloatEntry("foo", Float.valueOf(2.718281828f));

    assertKey("foo", entry);
    assertValue(2.718281828f, entry);

    assertTrue(entry.isNumericPrimitive());
    assertTrue(entry.is(TagMap.Entry.FLOAT));
  }

  static final void assertKey(String expected, TagMap.Entry entry) {
    assertEquals(expected, entry.tag());
    assertEquals(expected, entry.getKey());
  }

  static final void assertValue(Object expected, TagMap.Entry entry) {
    assertEquals(expected, entry.objectValue());
    assertEquals(expected, entry.getValue());

    assertEquals(expected.toString(), entry.stringValue());
  }

  static final void assertValue(boolean expected, TagMap.Entry entry) {
    assertEquals(expected, entry.booleanValue());
    assertEquals(Boolean.valueOf(expected), entry.objectValue());

    assertEquals(Boolean.toString(expected), entry.stringValue());
  }

  static final void assertValue(int expected, TagMap.Entry entry) {
    assertEquals(expected, entry.intValue());
    assertEquals((long) expected, entry.longValue());
    assertEquals((float) expected, entry.floatValue());
    assertEquals((double) expected, entry.doubleValue());
    assertEquals(Integer.valueOf(expected), entry.objectValue());

    assertEquals(Integer.toString(expected), entry.stringValue());
  }

  static final void assertValue(long expected, TagMap.Entry entry) {
    assertEquals(expected, entry.longValue());
    assertEquals((int) expected, entry.intValue());
    assertEquals((float) expected, entry.floatValue());
    assertEquals((double) expected, entry.doubleValue());
    assertEquals(Long.valueOf(expected), entry.objectValue());

    assertEquals(Long.toString(expected), entry.stringValue());
  }

  static final void assertValue(double expected, TagMap.Entry entry) {
    assertEquals(expected, entry.doubleValue());
    assertEquals((int) expected, entry.intValue());
    assertEquals((long) expected, entry.longValue());
    assertEquals((float) expected, entry.floatValue());
    assertEquals(Double.valueOf(expected), entry.objectValue());

    assertEquals(Double.toString(expected), entry.stringValue());
  }

  static final void assertValue(float expected, TagMap.Entry entry) {
    assertEquals(expected, entry.floatValue());
    assertEquals((int) expected, entry.intValue());
    assertEquals((long) expected, entry.longValue());
    assertEquals((double) expected, entry.doubleValue());
    assertEquals(Float.valueOf(expected), entry.objectValue());

    assertEquals(Float.toString(expected), entry.stringValue());
  }
}

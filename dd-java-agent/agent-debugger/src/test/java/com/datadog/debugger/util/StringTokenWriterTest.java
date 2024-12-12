package com.datadog.debugger.util;

import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_COLLECTION_SIZE;
import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_FIELD_COUNT;
import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_LENGTH;
import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_REFERENCE_DEPTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StringTokenWriterTest {

  private static final Limits DEPTH_0 =
      new Limits(0, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, DEFAULT_FIELD_COUNT);
  private static final Limits DEPTH_1 =
      new Limits(1, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, DEFAULT_FIELD_COUNT);

  @Test
  public void basic() throws Exception {
    assertEquals("null", serializeValue(null, DEPTH_0));
    assertEquals("...", serializeValue(new Object(), DEPTH_0));
    assertEquals(
        "foo", serializeValue("foo", DEPTH_0)); // String treated as primitive, no field extraction
  }

  @Test
  public void fields() throws Exception {
    assertEquals("...", serializeValue(new Person(), DEPTH_0));
  }

  @Test
  public void deepFields() throws Exception {
    assertEquals(
        "{strVal=strval, intVal=24, nullField=null, mapVal=..., objArray=...}",
        serializeValue(new Person(), DEPTH_1));
  }

  @Test
  public void primitiveFields() throws Exception {
    assertEquals("0", serializeValue((byte) 0, DEPTH_1));
    assertEquals("0", serializeValue((short) 0, DEPTH_1));
    assertEquals("*", serializeValue((char) 42, DEPTH_1));
    assertEquals("0", serializeValue(0, DEPTH_1));
    assertEquals("0", serializeValue(0L, DEPTH_1));
    assertEquals("0.0", serializeValue(0F, DEPTH_1));
    assertEquals("0.0", serializeValue(0D, DEPTH_1));
    assertEquals("true", serializeValue(true, DEPTH_1));
    assertEquals(
        "beae1807-f3b0-4ea8-a74f-826790c5e6f8",
        serializeValue(UUID.fromString("beae1807-f3b0-4ea8-a74f-826790c5e6f8"), DEPTH_1));
    assertEquals("java.util.Random", serializeValue(Random.class, DEPTH_1));
  }

  @Test
  public void lotsOfFields() throws Exception {
    assertEquals(
        "{f00=0, f01=1, f02=2, f03=3, f04=4}, ...",
        serializeValue(
            new LotsFields(),
            new Limits(DEFAULT_REFERENCE_DEPTH, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, 5)));
  }

  @Test
  public void parentFields() throws Exception {
    assertEquals(
        "{valueField=4, field3=3, field2=2, field1=1}",
        serializeValue(new LeafClass(), Limits.DEFAULT));
    assertEquals(
        "{valueField=4, field3=3}, ...",
        serializeValue(
            new LeafClass(),
            new Limits(DEFAULT_REFERENCE_DEPTH, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, 2)));
  }

  @Test
  public void collections() throws Exception {
    List<String> list = new ArrayList<>();
    list.add("foo");
    list.add("bar");
    list.add(null);
    assertEquals("[foo, bar, null]", serializeValue(list, DEPTH_1));
  }

  @Test
  public void maps() throws Exception {
    HashMap<String, String> map = new HashMap<>();
    map.put("foo1", "bar1");
    map.put(null, null);
    String serializedStr = serializeValue(map, DEPTH_1);
    assertTrue(serializedStr.contains("[foo1=bar1]"));
    assertTrue(serializedStr.contains("[null=null]"));
  }

  @Test
  public void collectionUnknown() throws Exception {
    class MyArrayList<T> extends ArrayList<T> {}
    String str = serializeValue(new MyArrayList<>(), DEPTH_1);
    assertTrue(str.contains("elementData="));
    assertTrue(str.contains("size="));
    assertTrue(str.contains("modCount="));
  }

  @Test
  public void mapUnknown() throws Exception {
    class MyMap<K, V> extends HashMap<K, V> {
      @Override
      public Set<Entry<K, V>> entrySet() {
        throw new UnsupportedOperationException("entrySet");
      }
    }
    String str = serializeValue(new MyMap<String, String>(), DEPTH_1);
    assertTrue(str.contains("table="));
    assertTrue(str.contains("size="));
    assertTrue(str.contains("modCount="));
    assertTrue(str.contains("threshold="));
    assertTrue(str.contains("loadFactor="));
  }

  static class Person {
    private static final String C1 = "constant1";
    private static final int C2 = 42;
    private static List<String> list = new ArrayList<>();
    private String strVal = "strval";
    private int intVal = 24;
    private Object nullField = null;
    private Map<String, String> mapVal = new HashMap<>();
    private Object[] objArray = new Object[] {new AtomicLong()};
  }

  static class LotsFields {
    private int f00 = 0;
    private int f01 = 1;
    private int f02 = 2;
    private int f03 = 3;
    private int f04 = 4;
    private int f05 = 5;
    private int f06 = 6;
    private int f07 = 7;
    private int f08 = 8;
    private int f09 = 9;
    private int f10 = 10;
  }

  static class Parent1 {
    private int field1 = 1;
  }

  static class Parent2 extends Parent1 {
    private int field2 = 2;
  }

  static class Parent3 extends Parent2 {
    private int field3 = 3;
  }

  static class LeafClass extends Parent3 {
    private int valueField = 4;
  }

  private String serializeValue(Object value, Limits limits) throws Exception {
    StringBuilder sb = new StringBuilder();
    SerializerWithLimits serializer =
        new SerializerWithLimits(
            new StringTokenWriter(sb, new ArrayList<>()),
            new TimeoutChecker(Duration.of(300, ChronoUnit.SECONDS)));
    serializer.serialize(
        value, value != null ? value.getClass().getTypeName() : Object.class.getTypeName(), limits);
    return sb.toString();
  }
}

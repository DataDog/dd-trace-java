package datadog.trace.bootstrap.debugger;

import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_COLLECTION_SIZE;
import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_FIELD_COUNT;
import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_LENGTH;
import static datadog.trace.bootstrap.debugger.Limits.DEFAULT_REFERENCE_DEPTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_17;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;

public class FieldExtractorTest {

  private static final Limits DEPTH_0 =
      new Limits(0, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, DEFAULT_FIELD_COUNT);
  private static final Limits DEPTH_1 =
      new Limits(1, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, DEFAULT_FIELD_COUNT);

  private static void onField(
      Field field,
      Object value,
      int maxDepth,
      Map<String, Snapshot.CapturedValue> results,
      Limits limits) {
    Map<String, Snapshot.CapturedValue> subFields =
        extract(
            value,
            new Limits(
                limits.maxReferenceDepth - 1,
                limits.maxCollectionSize,
                limits.maxLength,
                limits.maxFieldCount));
    Snapshot.CapturedValue capturedValue =
        Snapshot.CapturedValue.raw(
            field.getName(), field.getType().getName(), value, limits, subFields, null);
    results.put(field.getName(), capturedValue);
  }

  private static void handleExtractException(
      Exception ex, Field field, String className, Map<String, Snapshot.CapturedValue> results) {
    String fieldName = field.getName();
    Snapshot.CapturedValue notCapturedReason =
        Snapshot.CapturedValue.notCapturedReason(
            fieldName, field.getType().getName(), ex.toString());
    results.put(fieldName, notCapturedReason);
  }

  private static void onMaxFieldCount(
      Field f, Map<String, Snapshot.CapturedValue> results, int maxFieldCount, int totalFields) {
    String msg =
        String.format(
            "Max %d fields reached, %d fields were not captured",
            maxFieldCount, totalFields - maxFieldCount);
    results.put("@status", Snapshot.CapturedValue.notCapturedReason("@status", "", msg));
  }

  public static Map<String, Snapshot.CapturedValue> extract(Object obj, Limits limits) {
    Map<String, Snapshot.CapturedValue> results = new HashMap<>();
    FieldExtractor.extract(
        obj,
        limits,
        (field, value, maxDepth) -> onField(field, value, maxDepth, results, limits),
        (e, field) -> handleExtractException(e, field, "", results),
        (field, total) -> onMaxFieldCount(field, results, limits.maxFieldCount, total));
    return results;
  }

  @Test
  public void basic() {
    assertEquals(Collections.emptyMap(), extract(null, DEPTH_0));
    Map<String, Snapshot.CapturedValue> map = extract(new Object(), DEPTH_0);
    assertTrue(map.isEmpty());
    map = extract("null", DEPTH_0);
    assertTrue(map.isEmpty()); // String trated as primitive, no field extraction
  }

  @Test
  public void fields() {
    Map<String, Snapshot.CapturedValue> map = extract(new Person(), DEPTH_0);
    assertTrue(map.containsKey("list"));
    assertTrue(map.containsKey("strVal"));
    assertTrue(map.containsKey("intVal"));
    assertTrue(map.containsKey("mapVal"));
    assertTrue(map.containsKey("objArray"));
  }

  @Test
  public void deepFields() {
    Map<String, Snapshot.CapturedValue> capturedFields = extract(new Person(), DEPTH_1);
    Snapshot.CapturedValue list = capturedFields.get("list");
    Map<String, Snapshot.CapturedValue> listFields = list.getFields();
    assertNotNull(listFields);
    assertTrue(listFields.containsKey("size"));
    assertTrue(listFields.containsKey("elementData"));
    Snapshot.CapturedValue elementData = listFields.get("elementData");
    assertEquals(0, elementData.getFields().size()); // end the depth here

    Snapshot.CapturedValue map = capturedFields.get("mapVal");
    Map<String, Snapshot.CapturedValue> mapFields = map.getFields();
    assertNotNull(mapFields);
    assertTrue(mapFields.containsKey("entrySet"));
    assertTrue(mapFields.containsKey("threshold"));
    assertTrue(mapFields.containsKey("modCount"));
    assertTrue(mapFields.containsKey("size"));
    assertTrue(mapFields.containsKey("loadFactor"));
    assertTrue(mapFields.containsKey("table"));
    Snapshot.CapturedValue entrySet = mapFields.get("entrySet");
    assertEquals(0, entrySet.getFields().size());

    Snapshot.CapturedValue objArray = capturedFields.get("objArray");
    Map<String, Snapshot.CapturedValue> objArrayFields = objArray.getFields();
    assertEquals(0, objArrayFields.size());
  }

  @Test
  @EnabledOnJre({JAVA_17})
  public void inaccessibleObject() {
    Map<String, Snapshot.CapturedValue> capturedFields = extract(new Person(), DEPTH_1);
    Snapshot.CapturedValue list = capturedFields.get("list");
    Map<String, Snapshot.CapturedValue> listFields = list.getFields();
    assertNotNull(listFields);
    assertTrue(listFields.containsKey("size"));
    assertTrue(listFields.containsKey("elementData"));
    Snapshot.CapturedValue elementData = listFields.get("elementData");
    System.out.println("elementData capturedValue: " + elementData);
  }

  @Test
  public void primitiveFields() {
    assertEquals(0, extract((byte) 0, DEPTH_1).size());
    assertEquals(0, extract((short) 0, DEPTH_1).size());
    assertEquals(0, extract((char) 0, DEPTH_1).size());
    assertEquals(0, extract(0, DEPTH_1).size());
    assertEquals(0, extract(0L, DEPTH_1).size());
    assertEquals(0, extract(0F, DEPTH_1).size());
    assertEquals(0, extract(0D, DEPTH_1).size());
    assertEquals(0, extract(true, DEPTH_1).size());
  }

  @Test
  public void lotsOfFields() {
    Map<String, Snapshot.CapturedValue> fields =
        extract(
            new LotsFields(),
            new Limits(DEFAULT_REFERENCE_DEPTH, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, 5));
    assertEquals(6, fields.size());
    assertTrue(fields.containsKey("f00"));
    assertTrue(fields.containsKey("f01"));
    assertTrue(fields.containsKey("f02"));
    assertTrue(fields.containsKey("f03"));
    assertTrue(fields.containsKey("f04"));
    assertEquals(
        "Max 5 fields reached, 6 fields were not captured",
        fields.get("@status").getNotCapturedReason());
  }

  static class Person {
    private static final String C1 = "constant1";
    private static final int C2 = 42;
    private static List<String> list = new ArrayList<>();
    private String strVal = "strval";
    private int intVal = 24;
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
}

package datadog.trace.bootstrap.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_17;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;

public class FieldExtractorTest {

  private static final FieldExtractor.Limits DEPTH_0 =
      new FieldExtractor.Limits(0, FieldExtractor.DEFAULT_FIELD_COUNT);
  private static final FieldExtractor.Limits DEPTH_1 =
      new FieldExtractor.Limits(1, FieldExtractor.DEFAULT_FIELD_COUNT);

  @Test
  public void basic() {
    assertEquals(Collections.emptyMap(), FieldExtractor.extract(null, DEPTH_0));
    Map<String, Snapshot.CapturedValue> map = FieldExtractor.extract(new Object(), DEPTH_0);
    assertTrue(map.isEmpty());
    map = FieldExtractor.extract("null", DEPTH_0);
    assertTrue(map.size() > 0);
    assertTrue(map.containsKey("hash"));
    assertTrue(map.containsKey("value"));
    // other fields are implementation defined and vary from version to version
  }

  @Test
  public void fields() {
    Map<String, Snapshot.CapturedValue> map = FieldExtractor.extract(new Person(), DEPTH_0);
    assertTrue(map.containsKey("list"));
    assertTrue(map.containsKey("strVal"));
    assertTrue(map.containsKey("intVal"));
    assertTrue(map.containsKey("mapVal"));
    assertTrue(map.containsKey("objArray"));
  }

  @Test
  public void deepFields() {
    Map<String, Snapshot.CapturedValue> capturedFields =
        FieldExtractor.extract(new Person(), DEPTH_1);
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
    Map<String, Snapshot.CapturedValue> capturedFields =
        FieldExtractor.extract(new Person(), DEPTH_1);
    Snapshot.CapturedValue list = capturedFields.get("list");
    Map<String, Snapshot.CapturedValue> listFields = list.getFields();
    assertNotNull(listFields);
    assertTrue(listFields.containsKey("size"));
    assertTrue(listFields.containsKey("elementData"));
    Snapshot.CapturedValue elementData = listFields.get("elementData");
    System.out.println("elementData capturedValue: " + elementData);
    // assertNull(elementData.toString());
  }

  @Test
  public void primitiveFields() {
    assertEquals(0, FieldExtractor.extract((byte) 0, DEPTH_1).size());
    assertEquals(0, FieldExtractor.extract((short) 0, DEPTH_1).size());
    assertEquals(0, FieldExtractor.extract((char) 0, DEPTH_1).size());
    assertEquals(0, FieldExtractor.extract(0, DEPTH_1).size());
    assertEquals(0, FieldExtractor.extract(0L, DEPTH_1).size());
    assertEquals(0, FieldExtractor.extract(0F, DEPTH_1).size());
    assertEquals(0, FieldExtractor.extract(0D, DEPTH_1).size());
    assertEquals(0, FieldExtractor.extract(true, DEPTH_1).size());
  }

  @Test
  public void lotsOfFields() {
    Map<String, Snapshot.CapturedValue> fields =
        FieldExtractor.extract(new LotsFields(), new FieldExtractor.Limits(1, 5));
    assertEquals(6, fields.size());
    assertTrue(fields.containsKey("f00"));
    assertTrue(fields.containsKey("f01"));
    assertTrue(fields.containsKey("f02"));
    assertTrue(fields.containsKey("f03"));
    assertTrue(fields.containsKey("f04"));
    assertEquals(
        "Max 5 fields reached, 6 fields were not captured",
        fields.get("@status").getReasonNotCaptured());
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

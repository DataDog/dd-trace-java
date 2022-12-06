package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListValueTest {
  @Test
  void testFromList() {
    List<String> stringList = Arrays.asList("one", "two", "three", null);

    ListValue listValue = new ListValue(stringList);
    assertFalse(listValue.isEmpty());

    for (int i = 0; i < stringList.size(); i++) {
      Object expected = stringList.get(i);

      Value<?> v = listValue.get(i);
      assertNotNull(v);
      if (expected != null) {
        assertEquals(expected, v.getValue());
      } else {
        assertEquals(Value.nullValue(), v);
      }
    }
    assertEquals(Value.undefinedValue(), listValue.get(-1));
    assertEquals(Value.undefinedValue(), listValue.get(stringList.size()));
  }

  @Test
  void testFromUndefinedList() {
    ListValue listValue = new ListValue("a");
    assertTrue(listValue.isEmpty());

    assertEquals(Value.undefinedValue(), listValue.get(-1));
  }

  @Test
  void testFromArray() {
    Object[] array = new Object[] {1, 2, 3, null};

    ListValue listValue = new ListValue(array);
    assertFalse(listValue.isEmpty());

    for (int i = 0; i < array.length; i++) {
      Object expected = array[i];
      Value<?> v = listValue.get(i);
      assertNotNull(v);
      if (expected != null) {
        assertEquals(
            ((Integer) array[i]).longValue(), v.getValue()); // int is automatically widened to long
      } else {
        assertEquals(Value.nullValue(), v);
      }
    }
    assertEquals(Value.undefinedValue(), listValue.get(-1));
    assertEquals(Value.undefinedValue(), listValue.get(array.length));
  }

  @Test
  void testFromArrayOfArrays() {
    int[][] intArray = new int[][] {{1, 2, 3}, {2, 3, 4}};

    ListValue listValue = new ListValue(intArray);
    assertFalse(listValue.isEmpty());

    for (int i = 0; i < intArray.length; i++) {
      assertNotNull(listValue.get(i));
      Value<?> v = listValue.get(i);
      assertNotNull(v);
      ListValue collection = (ListValue) v;
      for (int j = 0; j < collection.count(); j++) {
        Value<?> v1 = collection.get(j);
        assertEquals((long) intArray[i][j], v1.getValue()); // int is automatically widened to long
      }
    }
    assertEquals(Value.undefinedValue(), listValue.get(-1));
    assertEquals(Value.undefinedValue(), listValue.get(intArray.length));
  }
}

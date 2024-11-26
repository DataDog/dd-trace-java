package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.bootstrap.debugger.CapturedContext;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class CapturedValueAdapterTest {

  static final String CAPTURED_VALUE_SIMPLE_TEMPLATE = "{\"type\": \"%s\", \"value\": \"%s\"}";
  private static final String CAPTURED_VALUE_COLLECTION_TEMPLATE =
      "{\"type\": \"%s\", \"elements\": [%s]}";
  private static final String CAPTURED_VALUE_MAP_TEMPLATE = "{\"type\": \"%s\", \"entries\": [%s]}";
  Moshi moshi = MoshiSnapshotTestHelper.createMoshiSnapshot();
  JsonAdapter<CapturedContext.CapturedValue> adapter =
      moshi.adapter(CapturedContext.CapturedValue.class);

  @Test
  void nullValue() throws IOException {
    CapturedContext.CapturedValue capturedValue =
        adapter.fromJson("{\"type\": \"String\", \"isNull\": true}");
    assertNull(capturedValue.getValue());
  }

  @Test
  void primitives() throws IOException {
    CapturedContext.CapturedValue value = getSimpleValue(Integer.TYPE.getTypeName(), "42");
    assertEquals(42, value.getValue());
    value = getSimpleValue(String.class.getTypeName(), "foobar");
    assertEquals("foobar", value.getValue());
    value = getSimpleValue(byte.class.getTypeName(), 42);
    assertEquals((byte) 42, value.getValue());
    value = getSimpleValue(char.class.getTypeName(), '*');
    assertEquals('*', value.getValue());
    value = getSimpleValue(short.class.getTypeName(), 42);
    assertEquals((short) 42, value.getValue());
    value = getSimpleValue(long.class.getTypeName(), 42L);
    assertEquals(42L, value.getValue());
    value = getSimpleValue(boolean.class.getTypeName(), true);
    assertEquals(true, value.getValue());
    value = getSimpleValue(float.class.getTypeName(), 42.1F);
    assertEquals(42.1F, value.getValue());
    value = getSimpleValue(double.class.getTypeName(), 42.1D);
    assertEquals(42.1D, value.getValue());
  }

  @Test
  void collection() throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(String.format(CAPTURED_VALUE_SIMPLE_TEMPLATE, int.class.getTypeName(), i));
    }
    CapturedContext.CapturedValue value =
        getCollectionValue(int[].class.getTypeName(), sb.toString());
    assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, (int[]) value.getValue());

    sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(
          String.format(CAPTURED_VALUE_SIMPLE_TEMPLATE, String.class.getTypeName(), "foo" + i));
    }
    value = getCollectionValue(String[].class.getTypeName(), sb.toString());
    assertArrayEquals(
        new String[] {
          "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8", "foo9"
        },
        (String[]) value.getValue());
  }

  @Test
  void map() throws IOException {
    String key = String.format(CAPTURED_VALUE_SIMPLE_TEMPLATE, String.class.getTypeName(), "foo");
    String value = String.format(CAPTURED_VALUE_SIMPLE_TEMPLATE, String.class.getTypeName(), "bar");
    CapturedContext.CapturedValue capturedValue =
        getMapValue(int[].class.getTypeName(), "[" + key + "," + value + "]");
    Map<String, String> stringMap = (Map<String, String>) capturedValue.getValue();
    assertEquals("bar", stringMap.get("foo"));
  }

  private CapturedContext.CapturedValue getSimpleValue(String type, Object value)
      throws IOException {
    return adapter.fromJson(String.format(CAPTURED_VALUE_SIMPLE_TEMPLATE, type, value));
  }

  private CapturedContext.CapturedValue getCollectionValue(String type, String elements)
      throws IOException {
    return adapter.fromJson(String.format(CAPTURED_VALUE_COLLECTION_TEMPLATE, type, elements));
  }

  private CapturedContext.CapturedValue getMapValue(String type, String entries)
      throws IOException {
    return adapter.fromJson(String.format(CAPTURED_VALUE_MAP_TEMPLATE, type, entries));
  }
}

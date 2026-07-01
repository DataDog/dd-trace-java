package datadog.trace.instrumentation.openai_java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.JsonValue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonValueUtilsTest {

  @Test
  void testNullReturnsNull() {
    assertNull(JsonValueUtils.jsonValueToObject(null));
  }

  @Test
  void testString() {
    assertEquals("hello", JsonValueUtils.jsonValueToObject(JsonValue.from("hello")));
  }

  @Test
  void testInteger() {
    Object result = JsonValueUtils.jsonValueToObject(JsonValue.from(42));
    assertInstanceOf(Number.class, result);
    assertEquals(42, ((Number) result).intValue());
  }

  @Test
  void testDouble() {
    Object result = JsonValueUtils.jsonValueToObject(JsonValue.from(3.14));
    assertInstanceOf(Number.class, result);
    assertEquals(3.14, ((Number) result).doubleValue(), 0.0001);
  }

  @Test
  void testBooleanTrue() {
    assertEquals(true, JsonValueUtils.jsonValueToObject(JsonValue.from(true)));
  }

  @Test
  void testBooleanFalse() {
    assertEquals(false, JsonValueUtils.jsonValueToObject(JsonValue.from(false)));
  }

  @Test
  void testFlatObject() {
    Map<String, Object> input = new HashMap<>();
    input.put("key1", "value1");
    input.put("key2", 123);

    Object result = JsonValueUtils.jsonValueToObject(JsonValue.from(input));

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) result;
    assertEquals("value1", map.get("key1"));
    assertEquals(123, ((Number) map.get("key2")).intValue());
  }

  @Test
  void testNestedObject() {
    Map<String, Object> inner = new HashMap<>();
    inner.put("x", "nested");
    Map<String, Object> outer = new HashMap<>();
    outer.put("inner", inner);

    Object result = JsonValueUtils.jsonValueToObject(JsonValue.from(outer));

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> outerMap = (Map<String, Object>) result;
    assertInstanceOf(Map.class, outerMap.get("inner"));
    @SuppressWarnings("unchecked")
    Map<String, Object> innerMap = (Map<String, Object>) outerMap.get("inner");
    assertEquals("nested", innerMap.get("x"));
  }

  @Test
  void testFlatArray() {
    Object result = JsonValueUtils.jsonValueToObject(JsonValue.from(Arrays.asList("a", "b", "c")));

    assertInstanceOf(List.class, result);
    @SuppressWarnings("unchecked")
    List<Object> list = (List<Object>) result;
    assertEquals(3, list.size());
    assertEquals("a", list.get(0));
    assertEquals("b", list.get(1));
    assertEquals("c", list.get(2));
  }

  @Test
  void testNestedArray() {
    Object result =
        JsonValueUtils.jsonValueToObject(
            JsonValue.from(Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3, 4))));

    assertInstanceOf(List.class, result);
    @SuppressWarnings("unchecked")
    List<Object> outer = (List<Object>) result;
    assertInstanceOf(List.class, outer.get(0));
    @SuppressWarnings("unchecked")
    List<Object> inner = (List<Object>) outer.get(0);
    assertEquals(1, ((Number) inner.get(0)).intValue());
  }

  @Test
  void testMixedArray() {
    Object result =
        JsonValueUtils.jsonValueToObject(JsonValue.from(Arrays.asList("text", 42, true)));

    assertInstanceOf(List.class, result);
    @SuppressWarnings("unchecked")
    List<Object> list = (List<Object>) result;
    assertEquals("text", list.get(0));
    assertEquals(42, ((Number) list.get(1)).intValue());
    assertEquals(true, list.get(2));
  }

  @Test
  void testEmptyObject() {
    Object result = JsonValueUtils.jsonValueToObject(JsonValue.from(new HashMap<>()));
    assertInstanceOf(Map.class, result);
    assertTrue(((Map<?, ?>) result).isEmpty());
  }

  @Test
  void testEmptyArray() {
    Object result = JsonValueUtils.jsonValueToObject(JsonValue.from(Arrays.asList()));
    assertInstanceOf(List.class, result);
    assertTrue(((List<?>) result).isEmpty());
  }

  @Test
  void testJsonValueMapToObject() {
    Map<String, JsonValue> input = new HashMap<>();
    input.put("str", JsonValue.from("hello"));
    input.put("num", JsonValue.from(7));
    input.put("bool", JsonValue.from(false));

    Map<String, Object> result = JsonValueUtils.jsonValueMapToObject(input);

    assertEquals("hello", result.get("str"));
    assertEquals(7, ((Number) result.get("num")).intValue());
    assertEquals(false, result.get("bool"));
  }

  @Test
  void testJsonValueMapToObjectEmpty() {
    Map<String, Object> result = JsonValueUtils.jsonValueMapToObject(new HashMap<>());
    assertTrue(result.isEmpty());
  }
}

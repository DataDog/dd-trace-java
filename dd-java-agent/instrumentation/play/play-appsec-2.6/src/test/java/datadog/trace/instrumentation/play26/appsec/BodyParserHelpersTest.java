package datadog.trace.instrumentation.play26.appsec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import play.api.libs.json.JsValue;

class BodyParserHelpersTest {

  private static JsValue parse(String json) {
    return play.api.libs.json.Json$.MODULE$.parse(json);
  }

  @Test
  void jsValueToJavaObject_nullInputReturnsNull() {
    assertNull(BodyParserHelpers.jsValueToJavaObject(null));
  }

  @Test
  void jsValueToJavaObject_jsNullReturnsNull() {
    assertNull(BodyParserHelpers.jsValueToJavaObject(parse("null")));
  }

  @Test
  void jsValueToJavaObject_string() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("\"hello\""));
    assertEquals("hello", result);
  }

  @Test
  void jsValueToJavaObject_number() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("42"));
    assertTrue(result instanceof BigDecimal);
    assertEquals(0, ((BigDecimal) result).compareTo(new BigDecimal("42")));
  }

  @Test
  void jsValueToJavaObject_booleanTrue() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("true"));
    assertEquals(Boolean.TRUE, result);
  }

  @Test
  void jsValueToJavaObject_booleanFalse() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("false"));
    assertEquals(Boolean.FALSE, result);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_object() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("{\"key\":\"value\",\"num\":1}"));
    assertTrue(result instanceof Map);
    Map<String, Object> map = (Map<String, Object>) result;
    assertEquals("value", map.get("key"));
    assertTrue(map.get("num") instanceof BigDecimal);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_array() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("[\"a\",\"b\",\"c\"]"));
    assertTrue(result instanceof List);
    List<Object> list = (List<Object>) result;
    assertEquals(3, list.size());
    assertEquals("a", list.get(0));
    assertEquals("b", list.get(1));
    assertEquals("c", list.get(2));
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_nestedObject() {
    Object result =
        BodyParserHelpers.jsValueToJavaObject(parse("{\"outer\":{\"inner\":\"deep\"}}"));
    assertTrue(result instanceof Map);
    Map<String, Object> outer = (Map<String, Object>) result;
    assertTrue(outer.get("outer") instanceof Map);
    Map<String, Object> inner = (Map<String, Object>) outer.get("outer");
    assertEquals("deep", inner.get("inner"));
  }

  @Test
  void jsValueToJavaObject_zeroRecursionReturnsNull() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("{\"key\":\"value\"}"), 0);
    assertNull(result);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_recursionLimitTruncatesNesting() {
    // depth=1 means the object itself is converted but children are null
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("{\"a\":{\"b\":\"val\"}}"), 1);
    assertTrue(result instanceof Map);
    Map<String, Object> map = (Map<String, Object>) result;
    // inner object exceeds depth so its value is null
    assertNull(map.get("a"));
  }
}

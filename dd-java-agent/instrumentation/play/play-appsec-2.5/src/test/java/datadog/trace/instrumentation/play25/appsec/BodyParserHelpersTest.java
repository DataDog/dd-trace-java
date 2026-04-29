package datadog.trace.instrumentation.play25.appsec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import play.api.libs.json.JsValue;
import play.api.mvc.MultipartFormData;

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
    // depth=1: outer object is converted, but children exceed the limit and become null
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("{\"a\":{\"b\":\"val\"}}"), 1);
    assertTrue(result instanceof Map);
    Map<String, Object> map = (Map<String, Object>) result;
    assertNull(map.get("a"));
  }

  // --- collectFilenames tests ---

  @Test
  void collectFilenames_emptyIterator() {
    List<String> result = BodyParserHelpers.collectFilenames(Collections.emptyIterator());
    assertTrue(result.isEmpty());
  }

  @Test
  void collectFilenames_nullFilenameExcluded() throws Exception {
    List<String> result =
        BodyParserHelpers.collectFilenames(
            Collections.<Object>singletonList(filePart("f", null)).iterator());
    assertTrue(result.isEmpty());
  }

  @Test
  void collectFilenames_emptyFilenameExcluded() throws Exception {
    List<String> result =
        BodyParserHelpers.collectFilenames(
            Collections.<Object>singletonList(filePart("f", "")).iterator());
    assertTrue(result.isEmpty());
  }

  @Test
  void collectFilenames_validFilenameIncluded() throws Exception {
    List<String> result =
        BodyParserHelpers.collectFilenames(
            Collections.<Object>singletonList(filePart("f", "evil.php")).iterator());
    assertEquals(Collections.singletonList("evil.php"), result);
  }

  @Test
  void collectFilenames_mixedPartsFiltered() throws Exception {
    List<Object> parts =
        Arrays.<Object>asList(
            filePart("f1", "a.pdf"),
            filePart("f2", null),
            filePart("f3", ""),
            filePart("f4", "b.jpg"));
    List<String> result = BodyParserHelpers.collectFilenames(parts.iterator());
    assertEquals(Arrays.asList("a.pdf", "b.jpg"), result);
  }

  @SuppressWarnings("unchecked")
  private static MultipartFormData.FilePart<Object> filePart(String key, String filename)
      throws Exception {
    // FilePart is a Scala case class nested in object MultipartFormData.
    // Use the companion object's apply() to avoid JVM inner-class constructor complexity.
    Class<?> companionClass = Class.forName("play.api.mvc.MultipartFormData$FilePart$");
    Object companion = companionClass.getField("MODULE$").get(null);
    for (Method m : companionClass.getMethods()) {
      if ("apply".equals(m.getName()) && m.getParameterCount() == 4) {
        return (MultipartFormData.FilePart<Object>)
            m.invoke(companion, key, filename, scala.None$.MODULE$, new Object());
      }
    }
    throw new IllegalStateException("FilePart.apply(4 params) not found");
  }
}

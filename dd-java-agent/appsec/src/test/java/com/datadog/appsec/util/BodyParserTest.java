package com.datadog.appsec.util;

import static com.datadog.appsec.ddwaf.WAFModule.MAX_DEPTH;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_ELEMENTS;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_STRING_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class BodyParserTest {

  private BodyParser parser;
  private BodyParser.State state;

  @BeforeEach
  void setUp() {
    parser = BodyParser.forJson();
    state = new BodyParser.State();
  }

  @TableTest({
    "scenario | media            | nonNull",
    "json     | application/json | true   ",
    "xml      | application/xml  | false  "
  })
  void testBodyParserPerMediaType(String media, boolean nonNull) {
    // when
    BodyParser result = BodyParser.forMediaType(media);

    // then
    assertEquals(nonNull, result != null);
  }

  @Test
  void testParseSimpleJsonObject() {
    // when
    Object result = parse("{\"name\":\"John\",\"age\":30}");

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    assertEquals("John", map.get("name"));
    assertEquals(30.0, map.get("age"));
    assertEquals(2, map.size());
    assertNoLimitReached();
  }

  @Test
  void testParseSimpleJsonArray() {
    // when
    Object result = parse("[1,2,3,\"test\"]");

    // then
    List<?> list = assertInstanceOf(List.class, result);
    assertEquals(4, list.size());
    assertEquals(1.0, list.get(0));
    assertEquals(2.0, list.get(1));
    assertEquals(3.0, list.get(2));
    assertEquals("test", list.get(3));
    assertNoLimitReached();
  }

  @Test
  void testParseJsonWithVariousDataTypes() {
    // when
    Object result =
        parse(
            "{\"string\":\"hello\",\"number\":42,\"boolean\":true,\"null\":null,"
                + "\"array\":[1,2],\"object\":{\"nested\":\"value\"}}");

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    assertEquals("hello", map.get("string"));
    assertEquals(42.0, map.get("number"));
    assertEquals(true, map.get("boolean"));
    assertNull(map.get("null"));
    List<?> array = assertInstanceOf(List.class, map.get("array"));
    assertEquals(2, array.size());
    Map<?, ?> object = assertInstanceOf(Map.class, map.get("object"));
    assertEquals("value", object.get("nested"));
    assertEquals(6, map.size());
  }

  @Test
  void testParseNestedJsonObject() {
    // when
    Object result = parse("{\"level1\":{\"level2\":{\"level3\":\"deep\"}}}");

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    Map<?, ?> level1 = assertInstanceOf(Map.class, map.get("level1"));
    Map<?, ?> level2 = assertInstanceOf(Map.class, level1.get("level2"));
    assertEquals("deep", level2.get("level3"));
    assertFalse(state.objectTooDeep);
  }

  @Test
  void testParseEmptyJsonObject() {
    // when
    Object result = parse("{}");

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    assertTrue(map.isEmpty());
  }

  @Test
  void testParseEmptyJsonArray() {
    // when
    Object result = parse("[]");

    // then
    List<?> list = assertInstanceOf(List.class, result);
    assertTrue(list.isEmpty());
  }

  @Test
  void testParseJsonWithSpecialCharacters() {
    // when
    Object result =
        parse(
            "{\"unicode\":\"\\u0048\\u0065\\u006c\\u006c\\u006f\","
                + "\"escaped\":\"line1\\nline2\\ttab\"}");

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    assertEquals("Hello", map.get("unicode"));
    assertEquals("line1\nline2\ttab", map.get("escaped"));
    assertEquals(2, map.size());
  }

  @Test
  void testIOExceptionWrappedInRuntimeException() {
    // given
    InputStream inputStream =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("Simulated IO error");
          }
        };

    // when
    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> parser.parse(state, inputStream));

    // then
    IOException cause = assertInstanceOf(IOException.class, ex.getCause());
    assertEquals("Simulated IO error", cause.getMessage());
  }

  @Test
  void testDepthLimitExceededObjectTooDeepFlagSet() {
    // when
    parse(generateDeepNestedJson(MAX_DEPTH + 5));

    // then
    assertTrue(state.objectTooDeep);
  }

  @Test
  void testStringLengthLimitExceededStringTooLongFlagSet() {
    // given
    String longString = repeat('a', MAX_STRING_SIZE + 10);

    // when
    Object result = parse("{\"longString\":\"" + longString + "\"}");

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    String parsedString = assertInstanceOf(String.class, map.get("longString"));
    assertTrue(parsedString.length() <= MAX_STRING_SIZE);
    assertTrue(state.stringTooLong);
  }

  @Test
  void testElementsLimitExceededInObjectListMapTooLargeFlagSet() {
    // when
    Object result = parse(generateLargeObjectJson(MAX_ELEMENTS + 10));

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    assertTrue(map.size() <= MAX_ELEMENTS);
    assertTrue(state.listMapTooLarge);
  }

  @Test
  void testElementsLimitExceededInArrayListMapTooLargeFlagSet() {
    // when
    Object result = parse(generateLargeArrayJson(MAX_ELEMENTS + 10));

    // then
    List<?> list = assertInstanceOf(List.class, result);
    assertTrue(list.size() <= MAX_ELEMENTS);
    assertTrue(state.listMapTooLarge);
  }

  @Test
  void testMixedNestedStructureWithLimits() {
    // when
    Object result =
        parse(
            "{\"array\":[1,2,3],\"object\":{\"nested\":{\"deep\":\"value\"}},\"string\":\"normal\"}");

    // then
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    assertInstanceOf(List.class, map.get("array"));
    assertInstanceOf(Map.class, map.get("object"));
    assertEquals("normal", map.get("string"));
    assertNoLimitReached();
  }

  @Test
  void testInvalidJsonThrowsJsonDataException() {
    // when
    RuntimeException ex = assertThrows(RuntimeException.class, () -> parse("{\"invalid\":}"));

    // then
    assertInstanceOf(Exception.class, ex.getCause());
  }

  private Object parse(String json) {
    return parser.parse(state, new ByteArrayInputStream(json.getBytes(UTF_8)));
  }

  private void assertNoLimitReached() {
    assertFalse(state.objectTooDeep);
    assertFalse(state.listMapTooLarge);
    assertFalse(state.stringTooLong);
  }

  private static String repeat(char character, int count) {
    StringBuilder sb = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      sb.append(character);
    }
    return sb.toString();
  }

  private static String generateDeepNestedJson(int depth) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      sb.append("{\"level").append(i).append("\":");
    }
    sb.append("\"deep\"");
    for (int i = 0; i < depth; i++) {
      sb.append('}');
    }
    return sb.toString();
  }

  private static String generateLargeObjectJson(int size) {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("\"key").append(i).append("\":\"value").append(i).append('"');
    }
    sb.append('}');
    return sb.toString();
  }

  private static String generateLargeArrayJson(int size) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(i);
    }
    sb.append(']');
    return sb.toString();
  }
}

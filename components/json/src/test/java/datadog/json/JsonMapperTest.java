package datadog.json;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class JsonMapperTest {
  @TableTest({
    "scenario        | input                        | expected                                    ",
    "null input      |                              | '{}'                                        ",
    "empty map       | [:]                          | '{}'                                        ",
    "single entry    | [key1: value1]               | '{\"key1\":\"value1\"}'                     ",
    "two entries     | [key1: value1, key2: value2] | '{\"key1\":\"value1\",\"key2\":\"value2\"}' "
  })
  void testMappingToJsonObject(Map<String, Object> input, String expected) throws IOException {
    assertMapToJsonRoundTrip(input, expected);
  }

  @Test
  void testMappingToJsonObjectWithComplexMap() throws IOException {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("key1", null);
    input.put("key2", "bar");
    input.put("key3", 3);
    input.put("key4", 3456789123L);
    input.put("key5", 3.142f);
    input.put("key6", Math.PI);
    input.put("key7", true);

    assertMapToJsonRoundTrip(
        input,
        "{\"key1\":null,\"key2\":\"bar\",\"key3\":3,\"key4\":3456789123,\"key5\":3.142,\"key6\":3.141592653589793,\"key7\":true}");
  }

  @Test
  void testMappingToJsonObjectWithQuotedEntries() throws IOException {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("key1", "va\"lu\"e1");
    input.put("ke\"y2", "value2");

    assertMapToJsonRoundTrip(input, "{\"key1\":\"va\\\"lu\\\"e1\",\"ke\\\"y2\":\"value2\"}");
  }

  @Test
  void testMappingToJsonObjectWithUnsupportedType() throws IOException {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("key1", new UnsupportedType());

    assertMapToJsonRoundTrip(input, "{\"key1\":\"toString\"}");
  }

  private void assertMapToJsonRoundTrip(Map<String, Object> input, String expected)
      throws IOException {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);

    Map<String, Object> parsed = JsonMapper.fromJsonToMap(json);
    if (input == null) {
      assertEquals(emptyMap(), parsed);
    } else {
      assertEquals(input.size(), parsed.size());
      for (Map.Entry<String, Object> entry : input.entrySet()) {
        String expectedKey = entry.getKey();
        Object expectedValue = entry.getValue();
        assertTrue(parsed.containsKey(expectedKey));
        Object parsedValue = parsed.get(expectedKey);
        if (expectedValue instanceof UnsupportedType) {
          assertEquals(expectedValue.toString(), parsedValue);
        } else if (expectedValue instanceof Float) {
          assertInstanceOf(Double.class, parsedValue);
          assertEquals((Float) expectedValue, (Double) parsedValue, 0.001);
        } else {
          assertEquals(expectedValue, parsedValue);
        }
      }
    }
  }

  @TableTest({
    "scenario      | json   ",
    "null          |        ",
    "null string   | 'null' ",
    "empty string  | ''     ",
    "empty object  | '{}'   "
  })
  void testMappingToMapFromEmptyJsonObject(String json) throws IOException {
    Map<String, Object> parsed = JsonMapper.fromJsonToMap(json);
    assertEquals(emptyMap(), parsed);
  }

  // temporary disable spotless, will open issue to fix this.
  // spotless:off
  @TableTest({
      "scenario  | json      ",
      "integer   | 1         ",
      "array     | '[1, 2]'  "
  })
  // spotless:on
  void testMappingToMapFromNonObjectJson(String json) {
    assertThrows(IOException.class, () -> JsonMapper.fromJsonToMap(json));
  }

  @TableTest({
    "scenario        | input               | expected                     ",
    "null input      |                     | '[]'                         ",
    "empty list      | []                  | '[]'                         ",
    "single value    | [value1]            | '[\"value1\"]'               ",
    "two values      | [value1, value2]    | '[\"value1\",\"value2\"]'    "
  })
  void testMappingIterableToJsonArray(List<String> input, String expected) throws IOException {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);

    List<String> parsed = JsonMapper.fromJsonToList(json);
    assertEquals(input != null ? input : emptyList(), parsed);
  }

  @Test
  void testMappingIterableToJsonArrayWithQuotedValue() throws IOException {
    List<String> input = Arrays.asList("va\"lu\"e1", "value2");
    String json = JsonMapper.toJson(input);
    assertEquals("[\"va\\\"lu\\\"e1\",\"value2\"]", json);

    List<String> parsed = JsonMapper.fromJsonToList(json);
    assertEquals(input, parsed);
  }

  @ParameterizedTest(name = "test mapping array to JSON array: {0}")
  @MethodSource("testMappingArrayToJsonArrayArguments")
  void testMappingArrayToJsonArray(String testCase, String[] input, String expected)
      throws IOException {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);

    String[] parsed = JsonMapper.fromJsonToList(json).toArray(new String[] {});
    assertArrayEquals(input != null ? input : new String[] {}, parsed);
  }

  static Stream<Arguments> testMappingArrayToJsonArrayArguments() {
    return Stream.of(
        arguments("null input", (Object) null, "[]"),
        arguments("empty array", new String[] {}, "[]"),
        arguments("single element", new String[] {"value1"}, "[\"value1\"]"),
        arguments("two elements", new String[] {"value1", "value2"}, "[\"value1\",\"value2\"]"),
        arguments(
            "escaped quotes",
            new String[] {"va\"lu\"e1", "value2"},
            "[\"va\\\"lu\\\"e1\",\"value2\"]"));
  }

  @TableTest({
    "scenario      | json   ",
    "null          |        ",
    "null string   | 'null' ",
    "empty string  | ''     ",
    "empty array   | '[]'   "
  })
  void testMappingToListFromEmptyJsonObject(String json) throws IOException {
    List<String> parsed = JsonMapper.fromJsonToList(json);
    assertEquals(emptyList(), parsed);
  }

  // Using `@MethodSource` as special chars not supported by `@TableTest` (yet?).
  @ParameterizedTest(name = "test mapping to JSON string: {0}")
  @MethodSource("testMappingToJsonStringArguments")
  void testMappingToJsonString(String input, String expected) {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);
  }

  static Stream<Arguments> testMappingToJsonStringArguments() {
    return Stream.of(
        arguments(null, ""),
        arguments("", ""),
        arguments(String.valueOf((char) 4096), "\"\\u1000\""),
        arguments(String.valueOf((char) 256), "\"\\u0100\""),
        arguments(String.valueOf((char) 128), "\"\\u0080\""),
        arguments("\b", "\"\\b\""),
        arguments("\t", "\"\\t\""),
        arguments("\n", "\"\\n\""),
        arguments("\f", "\"\\f\""),
        arguments("\r", "\"\\r\""),
        arguments("\"", "\"\\\"\""),
        arguments("/", "\"\\/\""),
        arguments("\\", "\"\\\\\""),
        arguments("a", "\"a\""));
  }

  private static class UnsupportedType {
    @Override
    public String toString() {
      return "toString";
    }
  }
}

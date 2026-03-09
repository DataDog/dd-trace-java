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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonMapperTest {

  @ParameterizedTest(name = "test mapping to JSON object: {0}")
  @MethodSource("testMappingToJsonObjectArguments")
  void testMappingToJsonObject(
      @SuppressWarnings("unused") String testCase, Map<String, Object> input, String expected)
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

  static Stream<Arguments> testMappingToJsonObjectArguments() {
    Map<String, Object> singleEntry = new LinkedHashMap<>();
    singleEntry.put("key1", "value1");

    Map<String, Object> twoEntries = new LinkedHashMap<>();
    twoEntries.put("key1", "value1");
    twoEntries.put("key2", "value2");

    Map<String, Object> quotedEntries = new LinkedHashMap<>();
    quotedEntries.put("key1", "va\"lu\"e1");
    quotedEntries.put("ke\"y2", "value2");

    Map<String, Object> complexMap = new LinkedHashMap<>();
    complexMap.put("key1", null);
    complexMap.put("key2", "bar");
    complexMap.put("key3", 3);
    complexMap.put("key4", 3456789123L);
    complexMap.put("key5", 3.142f);
    complexMap.put("key6", Math.PI);
    complexMap.put("key7", true);
    complexMap.put("key8", new UnsupportedType());

    return Stream.of(
        arguments("null input", null, "{}"),
        arguments("empty map", new HashMap<>(), "{}"),
        arguments("single entry", singleEntry, "{\"key1\":\"value1\"}"),
        arguments("two entries", twoEntries, "{\"key1\":\"value1\",\"key2\":\"value2\"}"),
        arguments(
            "quoted entries",
            quotedEntries,
            "{\"key1\":\"va\\\"lu\\\"e1\",\"ke\\\"y2\":\"value2\"}"),
        arguments(
            "complex map",
            complexMap,
            "{\"key1\":null,\"key2\":\"bar\",\"key3\":3,\"key4\":3456789123,\"key5\":3.142,\"key6\":3.141592653589793,\"key7\":true,\"key8\":\"toString\"}"));
  }

  @ParameterizedTest(name = "test mapping to Map from empty JSON object: {0}")
  @MethodSource("testMappingToMapFromEmptyJsonObjectArguments")
  void testMappingToMapFromEmptyJsonObject(String json) throws IOException {
    Map<String, Object> parsed = JsonMapper.fromJsonToMap(json);
    assertEquals(emptyMap(), parsed);
  }

  static Stream<Arguments> testMappingToMapFromEmptyJsonObjectArguments() {
    return Stream.of(arguments((Object) null), arguments("null"), arguments(""), arguments("{}"));
  }

  @ParameterizedTest(name = "test mapping to Map from non-object JSON: {0}")
  @ValueSource(strings = {"1", "[1, 2]"})
  void testMappingToMapFromNonObjectJson(String json) {
    assertThrows(IOException.class, () -> JsonMapper.fromJsonToMap(json));
  }

  @ParameterizedTest(name = "test mapping iterable to JSON array: {0}")
  @MethodSource("testMappingIterableToJsonArrayArguments")
  void testMappingIterableToJsonArray(List<String> input, String expected) throws IOException {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);

    List<String> parsed = JsonMapper.fromJsonToList(json);
    assertEquals(input != null ? input : emptyList(), parsed);
  }

  static Stream<Arguments> testMappingIterableToJsonArrayArguments() {
    return Stream.of(
        arguments(null, "[]"),
        arguments(new ArrayList<>(), "[]"),
        arguments(Arrays.asList("value1"), "[\"value1\"]"),
        arguments(Arrays.asList("value1", "value2"), "[\"value1\",\"value2\"]"),
        arguments(Arrays.asList("va\"lu\"e1", "value2"), "[\"va\\\"lu\\\"e1\",\"value2\"]"));
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

  @ParameterizedTest(name = "test mapping to List from empty JSON object: {0}")
  @MethodSource("testMappingToListFromEmptyJsonObjectArguments")
  void testMappingToListFromEmptyJsonObject(String json) throws IOException {
    List<String> parsed = JsonMapper.fromJsonToList(json);
    assertEquals(emptyList(), parsed);
  }

  static Stream<Arguments> testMappingToListFromEmptyJsonObjectArguments() {
    return Stream.of(arguments((Object) null), arguments("null"), arguments(""), arguments("[]"));
  }

  @ParameterizedTest(name = "test mapping to JSON string: {0}")
  @MethodSource("testMappingToJsonStringArguments")
  void testMappingToJsonString(String input, String expected) {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);
  }

  static Stream<Arguments> testMappingToJsonStringArguments() {
    return Stream.of(
        arguments((Object) null, ""),
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

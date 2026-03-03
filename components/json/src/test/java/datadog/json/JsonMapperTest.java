package datadog.json;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  @MethodSource("testMappingToJsonObject_arguments")
  void testMappingToJsonObject(Map<String, Object> input, String expected) throws IOException {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);

    Map<String, Object> parsed = JsonMapper.fromJsonToMap(json);
    if (input == null) {
      assertEquals(emptyMap(), parsed);
    } else {
      assertEquals(input.size(), parsed.size());
      for (Map.Entry<String, Object> entry : input.entrySet()) {
        assertTrue(parsed.containsKey(entry.getKey()));
        if (entry.getValue() instanceof UnsupportedType) {
          assertEquals(entry.getValue().toString(), parsed.get(entry.getKey()));
        } else if (entry.getValue() instanceof Float) {
          assertTrue(parsed.get(entry.getKey()) instanceof Double);
          assertEquals((Float) entry.getValue(), (Double) parsed.get(entry.getKey()), 0.001);
        } else {
          assertEquals(entry.getValue(), parsed.get(entry.getKey()));
        }
      }
    }
  }

  static Stream<Arguments> testMappingToJsonObject_arguments() {
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
        Arguments.of(null, "{}"),
        Arguments.of(new HashMap<>(), "{}"),
        Arguments.of(singleEntry, "{\"key1\":\"value1\"}"),
        Arguments.of(twoEntries, "{\"key1\":\"value1\",\"key2\":\"value2\"}"),
        Arguments.of(quotedEntries, "{\"key1\":\"va\\\"lu\\\"e1\",\"ke\\\"y2\":\"value2\"}"),
        Arguments.of(
            complexMap,
            "{\"key1\":null,\"key2\":\"bar\",\"key3\":3,\"key4\":3456789123,\"key5\":3.142,\"key6\":3.141592653589793,\"key7\":true,\"key8\":\"toString\"}"));
  }

  @ParameterizedTest(name = "test mapping to Map from empty JSON object: {0}")
  @MethodSource("testMappingToMapFromEmptyJsonObject_arguments")
  void testMappingToMapFromEmptyJsonObject(String json) throws IOException {
    Map<String, Object> parsed = JsonMapper.fromJsonToMap(json);
    assertEquals(emptyMap(), parsed);
  }

  static Stream<Arguments> testMappingToMapFromEmptyJsonObject_arguments() {
    return Stream.of(
        Arguments.of((Object) null), Arguments.of("null"), Arguments.of(""), Arguments.of("{}"));
  }

  @ParameterizedTest(name = "test mapping to Map from non-object JSON: {0}")
  @ValueSource(strings = {"1", "[1, 2]"})
  void testMappingToMapFromNonObjectJson(String json) {
    assertThrows(IOException.class, () -> JsonMapper.fromJsonToMap(json));
  }

  @ParameterizedTest(name = "test mapping iterable to JSON array: {0}")
  @MethodSource("testMappingIterableToJsonArray_arguments")
  void testMappingIterableToJsonArray(List<String> input, String expected) throws IOException {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);

    List<String> parsed = JsonMapper.fromJsonToList(json);
    assertEquals(input != null ? input : emptyList(), parsed);
  }

  static Stream<Arguments> testMappingIterableToJsonArray_arguments() {
    return Stream.of(
        Arguments.of(null, "[]"),
        Arguments.of(new ArrayList<>(), "[]"),
        Arguments.of(Arrays.asList("value1"), "[\"value1\"]"),
        Arguments.of(Arrays.asList("value1", "value2"), "[\"value1\",\"value2\"]"),
        Arguments.of(Arrays.asList("va\"lu\"e1", "value2"), "[\"va\\\"lu\\\"e1\",\"value2\"]"));
  }

  @ParameterizedTest(name = "test mapping array to JSON array: {0}")
  @MethodSource("testMappingArrayToJsonArray_arguments")
  void testMappingArrayToJsonArray(String[] input, String expected) throws IOException {
    String json = JsonMapper.toJson(input);
    assertEquals(expected, json);

    String[] parsed = JsonMapper.fromJsonToList(json).toArray(new String[0]);
    assertArrayEquals(input != null ? input : new String[0], parsed);
  }

  static Stream<Arguments> testMappingArrayToJsonArray_arguments() {
    return Stream.of(
        Arguments.of((Object) null, "[]"),
        Arguments.of(new String[0], "[]"),
        Arguments.of(new String[] {"value1"}, "[\"value1\"]"),
        Arguments.of(new String[] {"value1", "value2"}, "[\"value1\",\"value2\"]"),
        Arguments.of(new String[] {"va\"lu\"e1", "value2"}, "[\"va\\\"lu\\\"e1\",\"value2\"]"));
  }

  @ParameterizedTest(name = "test mapping to List from empty JSON object: {0}")
  @MethodSource("testMappingToListFromEmptyJsonObject_arguments")
  void testMappingToListFromEmptyJsonObject(String json) throws IOException {
    List<String> parsed = JsonMapper.fromJsonToList(json);
    assertEquals(emptyList(), parsed);
  }

  static Stream<Arguments> testMappingToListFromEmptyJsonObject_arguments() {
    return Stream.of(
        Arguments.of((Object) null), Arguments.of("null"), Arguments.of(""), Arguments.of("[]"));
  }

  @ParameterizedTest(name = "test mapping to JSON string: {0}")
  @MethodSource("testMappingToJsonString_arguments")
  void testMappingToJsonString(String input, String expected) {
    String escaped = JsonMapper.toJson(input);
    assertEquals(expected, escaped);
  }

  static Stream<Arguments> testMappingToJsonString_arguments() {
    return Stream.of(
        Arguments.of((Object) null, ""),
        Arguments.of("", ""),
        Arguments.of(String.valueOf((char) 4096), "\"\\u1000\""),
        Arguments.of(String.valueOf((char) 256), "\"\\u0100\""),
        Arguments.of(String.valueOf((char) 128), "\"\\u0080\""),
        Arguments.of("\b", "\"\\b\""),
        Arguments.of("\t", "\"\\t\""),
        Arguments.of("\n", "\"\\n\""),
        Arguments.of("\f", "\"\\f\""),
        Arguments.of("\r", "\"\\r\""),
        Arguments.of("\"", "\"\\\"\""),
        Arguments.of("/", "\"\\/\""),
        Arguments.of("\\", "\"\\\\\""),
        Arguments.of("a", "\"a\""));
  }

  private static class UnsupportedType {
    @Override
    public String toString() {
      return "toString";
    }
  }
}

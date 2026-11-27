package com.datadog.appsec.event.data;

import static com.datadog.appsec.ddwaf.WAFModule.MAX_DEPTH;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_ELEMENTS;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_STRING_SIZE;
import static com.datadog.appsec.event.data.ObjectIntrospection.convert;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.datadog.appsec.gateway.AppSecRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullTextJsonNode;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.lang.ref.SoftReference;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;

class ObjectIntrospectionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WafMetricCollector originalMetricCollector;
  private AppSecRequestContext ctx;
  private WafMetricCollector wafMetricCollector;

  @BeforeEach
  void setUp() {
    originalMetricCollector = WafMetricCollector.get();
    ctx = mock(AppSecRequestContext.class);
    wafMetricCollector = mock(WafMetricCollector.class);
    WafMetricCollector.INSTANCE = wafMetricCollector;
  }

  @AfterEach
  void tearDown() {
    WafMetricCollector.INSTANCE = originalMetricCollector;
  }

  @Test
  void nullIsPreserved() {
    assertNull(convert(null, ctx));
  }

  @ParameterizedTest(name = "type {1} is preserved")
  @MethodSource("typeIsPreservedArguments")
  void typeIsPreserved(Object input, Class<?> type) {
    // when
    Object result = convert(input, ctx);

    // then
    assertEquals(type, input.getClass());
    assertEquals(type, result.getClass());
    assertEquals(input, result);
  }

  static Stream<Arguments> typeIsPreservedArguments() {
    return Stream.of(
        arguments("hello", String.class),
        arguments(true, Boolean.class),
        arguments((byte) 1, Byte.class),
        arguments((short) 1, Short.class),
        arguments(1, Integer.class),
        arguments(1L, Long.class),
        arguments(1.0F, Float.class),
        arguments(1.0D, Double.class),
        arguments(BigInteger.ONE, BigInteger.class),
        arguments(new BigDecimal("1.0"), BigDecimal.class));
  }

  @ParameterizedTest(name = "type {1} is converted to string")
  @MethodSource("typeIsConvertedToStringArguments")
  void typeIsConvertedToString(Object input, Class<?> type, String output) {
    // when
    Object result = convert(input, ctx);

    // then
    assertTrue(type.isAssignableFrom(input.getClass()));
    assertInstanceOf(String.class, result);
    assertEquals(output, result);
  }

  static Stream<Arguments> typeIsConvertedToStringArguments() {
    return Stream.of(
        arguments('a', Character.class, "a"),
        arguments(createCharBuffer("hello"), CharBuffer.class, "hello"));
  }

  private static CharBuffer createCharBuffer(String content) {
    CharBuffer charBuffer = CharBuffer.allocate(content.length());
    charBuffer.put(content);
    charBuffer.position(0);
    return charBuffer;
  }

  @Test
  void iterablesAreConvertedToLists() {
    List<String> elements = asList("a", "b");
    Iterable<String> iterable = elements::iterator;

    assertInstanceOf(List.class, convert(iterable, ctx));
    assertEquals(elements, convert(iterable, ctx));
    assertEquals(elements, convert(asList("a", "b"), ctx));
  }

  @Test
  void mapsAreConvertedToHashMaps() {
    Map<String, String> expected = singletonMap("a", "b");
    Map<String, String> map =
        new AbstractMap<String, String>() {
          @Override
          public Set<Entry<String, String>> entrySet() {
            return expected.entrySet();
          }
        };

    assertInstanceOf(HashMap.class, convert(map, ctx));
    assertEquals(expected, convert(map, ctx));
    assertEquals(singletonMap("6", "b"), convert(singletonMap(6, "b"), ctx));
    assertEquals(singletonMap("null", "b"), convert(singletonMap(null, "b"), ctx));
    assertEquals(singletonMap("true", "b"), convert(singletonMap(true, "b"), ctx));
    assertEquals(singletonMap("a", "b"), convert(singletonMap('a', "b"), ctx));
    assertEquals(singletonMap("a", "b"), convert(singletonMap(createCharBuffer("a"), "b"), ctx));
  }

  @Test
  void arraysAreConvertedIntoLists() {
    assertEquals(asList(6, "b"), convert(new Object[] {6, "b"}, ctx));
    assertEquals(asList(null, null), convert(new Object[] {null, null}, ctx));
    assertEquals(asList(1, 2), convert(new int[] {1, 2}, ctx));
    assertEquals(asList((byte) 1, (byte) 2), convert(new byte[] {1, 2}, ctx));
  }

  @SuppressWarnings("unused")
  static class ClassToBeConverted {
    private static String X = "ignored";
    private String a = "b";
    private List<Integer> l = asList(1, 2);
  }

  @SuppressWarnings("unused")
  class ClassToBeConvertedExt extends ClassToBeConverted {
    private String c = "d";
  }

  @Test
  void otherObjectsAreConvertedIntoHashMaps() {
    Map<String, Object> expected = new HashMap<>();
    expected.put("c", "d");
    expected.put("a", "b");
    expected.put("l", asList(1, 2));

    assertInstanceOf(HashMap.class, convert(new ClassToBeConverted(), ctx));
    assertEquals(expected, convert(new ClassToBeConvertedExt(), ctx));
  }

  @SuppressWarnings("unused")
  class ProtobufLikeClass {
    String c = "d";
    int memoizedHashCode = 1;
    int memoizedSize = 2;
  }

  @Test
  void someFieldNamesAreIgnored() {
    assertInstanceOf(HashMap.class, convert(new ProtobufLikeClass(), ctx));
    assertEquals(singletonMap("c", "d"), convert(new ProtobufLikeClass(), ctx));
  }

  @Test
  void invalidKeysAreConvertedToSpecialStrings() {
    // insertion order matters: invalid keys are numbered in iteration order
    Map<Object, String> twoInvalidKeys = new LinkedHashMap<>();
    twoInvalidKeys.put(new ClassToBeConverted(), "a");
    twoInvalidKeys.put(new ClassToBeConverted(), "b");
    Map<String, String> expected = new HashMap<>();
    expected.put("invalid_key:1", "a");
    expected.put("invalid_key:2", "b");

    assertEquals(
        singletonMap("invalid_key:1", "a"),
        convert(singletonMap(new ClassToBeConverted(), "a"), ctx));
    assertEquals(expected, convert(twoInvalidKeys, ctx));
    assertEquals(singletonMap("invalid_key:1", "a"), convert(singletonMap(asList(1, 2), "a"), ctx));
  }

  @Test
  void maxNumberOfElementsIsHonored() {
    // setup
    Map<Integer, String> map = new HashMap<>();
    for (int i = 0; i < 128; i++) {
      map.put(i, "b");
    }
    String[] letters = new String[255];
    Arrays.fill(letters, "a");

    // when
    List<?> result1 = (List<?>) ((List<?>) convert(singletonList(asList(letters)), ctx)).get(0);
    List<?> result2 = (List<?>) ((List<?>) convert(singletonList(letters), ctx)).get(0);
    Map<?, ?> result3 = (Map<?, ?>) convert(map, ctx);

    // then
    assertEquals(254, result1.size()); // +2 for the lists
    assertEquals(254, result2.size()); // +2 for the lists
    assertEquals(127, result3.size()); // +1 for the map, 2 for each entry (key and value)
    verify(ctx, times(2)).setWafTruncated();
    verify(wafMetricCollector, times(2)).wafInputTruncated(false, true, false);
  }

  @Test
  void maxDepthIsHonoredArrayVersion() {
    // setup
    // Build a nested array 22 levels deep
    Object[] objArray = new Object[1];
    Object[] current = objArray;
    for (int i = 0; i < 22; i++) {
      Object[] child = new Object[1];
      current[0] = child;
      current = child;
    }

    // when
    // Invoke conversion with context
    Object result = convert(objArray, ctx);

    // then
    // Traverse converted arrays to count actual depth
    int depth = 0;
    for (Object node = result; node != null; node = ((List<?>) node).get(0)) {
      depth++;
    }
    assertEquals(21, depth); // after max depth we have nulls

    // Should record a truncation due to depth
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(false, false, true);
  }

  @Test
  void maxDepthIsHonoredListVersion() {
    // setup
    // Build a nested list 22 levels deep
    List<Object> list = new ArrayList<>();
    List<Object> current = list;
    for (int i = 0; i < 22; i++) {
      List<Object> child = new ArrayList<>();
      current.add(child);
      current = child;
    }

    // when
    // Invoke conversion with context
    Object result = convert(list, ctx);

    // then
    // Traverse converted lists to count actual depth
    int depth = 0;
    for (Object node = result; node != null; node = ((List<?>) node).get(0)) {
      depth++;
    }
    assertEquals(21, depth); // after max depth we have nulls

    // Should record a truncation due to depth
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(false, false, true);
  }

  @Test
  void maxDepthIsHonoredMapVersion() {
    // setup
    // Build a nested map 22 levels deep under key 'a'
    Map<String, Object> map = new HashMap<>();
    Map<String, Object> current = map;
    for (int i = 0; i < 22; i++) {
      Map<String, Object> child = new HashMap<>();
      current.put("a", child);
      current = child;
    }

    // when
    // Invoke conversion with context
    Object result = convert(map, ctx);

    // then
    // Traverse converted maps to count actual depth
    int depth = 0;
    for (Object node = result; node != null; node = ((Map<?, ?>) node).get("a")) {
      depth++;
    }
    assertEquals(21, depth); // after max depth we have nulls

    // Should record a truncation due to depth
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(false, false, true);
  }

  @ParameterizedTest(name = "truncate long {0} to 4096 chars and set truncation flag")
  @MethodSource("longCharSequenceArguments")
  void truncateLongCharSequenceTo4096CharsAndSetTruncationFlag(
      String typeName, CharSequence longInput) {
    // when
    Object converted = convert(longInput, ctx);

    // then
    // Should always produce a String of exactly 4096 chars
    assertInstanceOf(String.class, converted);
    assertEquals(4096, ((String) converted).length());

    // Should record a truncation due to string length
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(true, false, false);
  }

  @ParameterizedTest(
      name = "truncate long {0} when used as map key to 4096 chars and set truncation flag")
  @MethodSource("longCharSequenceArguments")
  void truncateLongMapKeyTo4096CharsAndSetTruncationFlag(String typeName, CharSequence longKey) {
    // setup
    // Build a map whose only key is a very long string/CharSequence
    Map<CharSequence, String> inputMap = singletonMap(longKey, "value");

    // when
    Map<?, ?> converted = (Map<?, ?>) convert(inputMap, ctx);

    // then
    // Extract the single truncated key
    String truncatedKey = (String) converted.keySet().iterator().next();

    // Key must be exactly 4096 characters
    assertEquals(4096, truncatedKey.length());

    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(true, false, false);
  }

  static Stream<Arguments> longCharSequenceArguments() {
    return Stream.of(
        arguments("String", repeat('A', 5000)),
        arguments("StringBuilder", new StringBuilder(repeat('B', 6000))));
  }

  @Test
  void conversionOfAnElementThrows() {
    CharSequence charSequence =
        new CharSequence() {
          @Override
          public int length() {
            return 0;
          }

          @Override
          public char charAt(int index) {
            return "".charAt(index);
          }

          @Override
          public CharSequence subSequence(int start, int end) {
            return "".subSequence(start, end);
          }

          @Override
          public String toString() {
            throw new RuntimeException("my exception");
          }
        };

    assertEquals(singletonList("error:my exception"), convert(singletonList(charSequence), ctx));
  }

  @Test
  void truncatedConversionTriggersTruncationListenerIfAvailable() {
    // setup
    ObjectIntrospection.TruncationListener listener =
        mock(ObjectIntrospection.TruncationListener.class);
    String object = repeat('A', 5000);

    // when
    convert(object, ctx, listener);

    // then
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(true, false, false);
    verify(listener).onTruncation();
  }

  @ParameterizedTest(name = "jackson node {0}")
  @MethodSource("jacksonNodeTypesComprehensiveCoverageArguments")
  void jacksonNodeTypesComprehensiveCoverage(String json, Object expected) throws Exception {
    assertJacksonConversion(json, expected);
  }

  static Stream<Arguments> jacksonNodeTypesComprehensiveCoverageArguments() {
    return Stream.of(
        arguments("null", null),
        arguments("true", true),
        arguments("false", false),
        arguments("42", 42),
        arguments("3.14", 3.14),
        arguments("\"hello\"", "hello"),
        arguments("[]", emptyList()),
        arguments("{}", emptyMap()),
        arguments("[1, 2, 3]", asList(1, 2, 3)),
        arguments("{\"key\": \"value\"}", singletonMap("key", "value")));
  }

  @Test
  void jacksonTextNodesWithNullTextualValueAreHandledGracefully() {
    // given
    NullTextJsonNode node = new NullTextJsonNode();

    // expect
    assertNull(convert(node, ctx));
  }

  @ParameterizedTest(name = "jackson nested structure {0}")
  @MethodSource("jacksonNestedStructuresArguments")
  void jacksonNestedStructures(String json, Object expected) throws Exception {
    assertJacksonConversion(json, expected);
  }

  static Stream<Arguments> jacksonNestedStructuresArguments() {
    return Stream.of(
        arguments(
            "{\"a\": {\"b\": {\"c\": 123}}}",
            singletonMap("a", singletonMap("b", singletonMap("c", 123)))),
        arguments(
            "[[[1, 2]], [[3, 4]]]",
            asList(singletonList(asList(1, 2)), singletonList(asList(3, 4)))),
        arguments("{\"arr\": [1, null, true]}", singletonMap("arr", asList(1, null, true))),
        arguments("[{\"x\": 1}, {\"y\": 2}]", asList(singletonMap("x", 1), singletonMap("y", 2))));
  }

  @ParameterizedTest(name = "jackson edge case {0}")
  @MethodSource("jacksonEdgeCasesArguments")
  void jacksonEdgeCases(String json, Object expected) throws Exception {
    assertJacksonConversion(json, expected);
  }

  static Stream<Arguments> jacksonEdgeCasesArguments() {
    return Stream.of(
        arguments("\"\"", ""),
        arguments("0", 0),
        arguments("-1", -1),
        arguments("9223372036854775807", Long.MAX_VALUE),
        arguments("1.7976931348623157E308", Double.MAX_VALUE),
        arguments("{\"\": \"empty_key\"}", singletonMap("", "empty_key")),
        arguments("{\"null_value\": null}", singletonMap("null_value", null)));
  }

  @Test
  void jacksonStringTruncation() throws Exception {
    // setup
    String longString = repeat('A', MAX_STRING_SIZE + 1);
    String jsonInput = "{\"long\": \"" + longString + "\"}";

    // when
    Map<?, ?> result = (Map<?, ?>) convert(MAPPER.readTree(jsonInput), ctx);

    // then
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(true, false, false);
    assertTrue(((String) result.get("long")).length() <= MAX_STRING_SIZE);
  }

  @Test
  void jacksonWithDeepNestingTriggersDepthLimit() throws Exception {
    // setup
    // Create deeply nested JSON
    Map<String, Object> nested = new HashMap<>();
    for (int i = 1; i <= MAX_DEPTH + 1; i++) {
      nested = singletonMap("child_" + i, nested);
    }
    String json = MAPPER.writeValueAsString(nested);

    // when
    Object result = convert(MAPPER.readTree(json), ctx);

    // then
    // Should truncate at max depth and set truncation flag
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(false, false, true);
    assertTrue(countNesting((Map<?, ?>) result, 0) <= MAX_DEPTH);
  }

  @Test
  void jacksonWithLargeArraysTriggersElementLimit() throws Exception {
    // setup
    // Create large array
    List<Integer> largeArray = new ArrayList<>();
    for (int i = 1; i <= MAX_ELEMENTS + 1; i++) {
      largeArray.add(i);
    }
    String json = MAPPER.writeValueAsString(largeArray);

    // when
    List<?> result = (List<?>) convert(MAPPER.readTree(json), ctx);

    // then
    // Should truncate and set truncation flag
    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(false, true, false);
    assertTrue(result.size() <= MAX_ELEMENTS);
  }

  @ParameterizedTest(name = "jackson number {0}")
  @MethodSource("jacksonNumberTypeVariationsArguments")
  void jacksonNumberTypeVariations(String json, Object expected) throws Exception {
    assertJacksonConversion(json, expected);
  }

  static Stream<Arguments> jacksonNumberTypeVariationsArguments() {
    return Stream.of(
        arguments("0", 0),
        arguments("1", 1),
        arguments("-1", -1),
        arguments("1.0", 1.0),
        arguments("1.5", 1.5),
        arguments("-1.5", -1.5),
        arguments("1e10", 1e10),
        arguments("1.23e-4", 1.23e-4));
  }

  @TableTest({
    "scenario       | json                   | expected    ",
    "tab            | '\"\\t\"'              | '\t'        ",
    "backslash      | '\"\\\\\"'             | '\\'        ",
    "quotes         | '\"\\\"quotes\\\"\"'   | '\"quotes\"'",
    "unicode escape | '\"unicode: \\u0041\"' | 'unicode: A'"
  })
  // line breaks cannot appear inside a table cell, so they come from a method source
  @MethodSource("jacksonSpecialStringValuesArguments")
  void jacksonSpecialStringValues(String json, String expected) throws Exception {
    assertJacksonConversion(json, expected);
  }

  static Stream<Arguments> jacksonSpecialStringValuesArguments() {
    return Stream.of(
        arguments(named("newline", "\"\\n\""), named("\\n", "\n")),
        arguments(named("carriage return", "\"\\r\""), named("\\r", "\r")));
  }

  @Test
  void loggingFrameworkFieldsAreExcludedFromIntrospection() {
    // given: Reproduces the false positive triggered by crs-944-130 (java_code_injection).
    // A request body DTO had an instance field of a concrete logging type. ObjectIntrospection
    // traversed the logger internals 19 levels deep (SLF4J → Log4jLogger → core.Logger →
    // privateConfig → loggerConfig → appenders → appenderArray → appender → manager →
    // layout → objectWriter → _config → _base → _typeFactory → _typeCache → _map →
    // invalid_key:9 → _elementType → _class), eventually calling
    // java.lang.Class.toString() = "class java.lang.object" which phrase-matched the WAF rule.
    // The field in the real trace was declared as org.slf4j.Logger (runtime: Log4jLogger),
    // but the trie also covers org.apache.logging.slf4j.Log4jLogger directly via
    // org.apache.logging.* in case the field is declared as the concrete class.
    DtoWithLogger input = new DtoWithLogger();

    // when
    Map<?, ?> result = (Map<?, ?>) convert(input, ctx);

    // then
    assertEquals("user123", result.get("userId"));
    assertEquals("data", result.get("payload"));
    assertFalse(result.containsKey("logger"));
    assertEquals(2, result.size());
  }

  @SuppressWarnings("unused")
  static class DtoWithLogger {
    String userId = "user123";
    // Declared as the concrete Logback type (ch.qos.logback.classic.Logger) to mirror the
    // real scenario where org.apache.logging.slf4j.Log4jLogger is the runtime instance of
    // a field declared as the concrete logger class rather than the SLF4J interface.
    // Both are covered by the trie (ch.qos.logback.* and org.apache.logging.*).
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("test");
    String payload = "data";
  }

  @Test
  void objectsWithInaccessibleJdkFieldsSkipThoseFieldsRatherThanExposeToString() {
    // given: An object with two fields: one normal, one holding a java.lang.ref.SoftReference.
    // java.lang.ref is NOT opened in the test JVM (only java.lang and java.util are),
    // so trySetAccessible() returns false for SoftReference's own fields on Java 9+.
    //
    // Bug: ObjectIntrospection fell back to obj.toString() when any field was inaccessible,
    // exposing internal JDK string representations to the WAF and discarding all other
    // accessible fields on the same object. For example, java.lang.Class.toString() produces
    // "class java.lang.Object" which matches WAF phrase_match rule crs-944-130
    // (java_code_injection) — a false positive causing a CPU spike.
    //
    // Fix: skip inaccessible fields (continue) instead of aborting the whole object.
    // Accessible fields on the same object are still reported to the WAF.
    WrapperWithSoftRef input = new WrapperWithSoftRef();

    // when
    Map<?, ?> result = (Map<?, ?>) convert(input, ctx);

    // then
    // The accessible field 'name' must be preserved regardless of JVM version
    assertEquals("test", result.get("name"));
    // The inaccessible-field object must NOT expose toString() to the WAF.
    // On JDK 8 and JDK 9-15 (--illegal-access=permit default), java.lang.ref fields are
    // accessible so result['ref'] is a non-empty Map. On JDK 16+ strict module enforcement,
    // result['ref'] is an empty Map [:] since all Reference fields are inaccessible.
    // Before fix (any version where fields are inaccessible): result['ref'] is a String
    //   e.g. "java.lang.ref.SoftReference@..." — a false WAF positive.
    // After fix: result['ref'] is always a Map, never a String.
    assertInstanceOf(Map.class, result.get("ref"));
  }

  @SuppressWarnings("unused")
  static class WrapperWithSoftRef {
    String name = "test";
    SoftReference<String> ref = new SoftReference<>("test");
  }

  @Test
  void iterableJsonObjects() {
    // setup
    Map<String, Object> map = new HashMap<>();
    map.put("name", "This is just a test");
    map.put("list", asList(1, 2, 3, 4, 5));

    // when
    Object result = convert(new IterableJsonObject(map), ctx);

    // then
    assertEquals(map, result);
  }

  private void assertJacksonConversion(String json, Object expected) throws Exception {
    // when
    Object result = convert(MAPPER.readTree(json), ctx);

    // then
    assertEquals(expected, result);
  }

  private static String repeat(char character, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, character);
    return new String(chars);
  }

  private static int countNesting(Map<?, ?> object, int levels) {
    if (object.isEmpty()) {
      return levels;
    }
    Object child = object.values().iterator().next();
    if (child == null) {
      return levels;
    }
    return countNesting((Map<?, ?>) child, levels + 1);
  }

  private static class IterableJsonObject implements Iterable<Map.Entry<String, Object>> {

    private final Map<String, Object> map;

    IterableJsonObject(Map<String, Object> map) {
      this.map = map;
    }

    @Override
    public Iterator<Map.Entry<String, Object>> iterator() {
      return map.entrySet().iterator();
    }
  }
}

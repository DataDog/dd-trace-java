package com.datadog.appsec.event.data;

import static com.datadog.appsec.ddwaf.WAFModule.MAX_DEPTH;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_ELEMENTS;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_STRING_SIZE;
import static com.datadog.appsec.event.data.ObjectIntrospection.convert;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Jackson 3 ({@code tools.jackson.databind}) counterpart of the Jackson 2 node specification. */
class ObjectIntrospectionJackson3Test {

  private static final ObjectMapper MAPPER = JsonMapper.shared();

  private WafMetricCollector originalMetricCollector;
  private WafMetricCollector wafMetricCollector;
  private AppSecRequestContext ctx;

  @BeforeEach
  void setUp() {
    originalMetricCollector = WafMetricCollector.get();
    wafMetricCollector = mock(WafMetricCollector.class);
    WafMetricCollector.INSTANCE = wafMetricCollector;
    ctx = mock(AppSecRequestContext.class);
  }

  @AfterEach
  void tearDown() {
    WafMetricCollector.INSTANCE = originalMetricCollector;
  }

  static Stream<Arguments> convertsJackson3NodesArguments() {
    return Stream.of(
        // scalars and empty containers
        arguments("null", null),
        arguments("true", true),
        arguments("false", false),
        arguments("42", 42),
        arguments("3.14", 3.14d),
        arguments("\"hello\"", "hello"),
        arguments("[]", emptyList()),
        arguments("{}", emptyMap()),
        arguments("[1, 2, 3]", asList(1, 2, 3)),
        arguments("{\"key\": \"value\"}", map("key", "value")),
        // object with every value type
        arguments(
            "{\"s\": \"str\", \"i\": 1, \"d\": 1.5, \"b\": true, \"n\": null,"
                + " \"o\": {\"k\": \"v\"}, \"strs\": [\"a\", \"b\"], \"nums\": [1, 2.5]}",
            map(
                "s",
                "str",
                "i",
                1,
                "d",
                1.5d,
                "b",
                true,
                "n",
                null,
                "o",
                map("k", "v"),
                "strs",
                asList("a", "b"),
                "nums",
                asList(1, 2.5d))),
        // nested structures
        arguments("{\"a\": {\"b\": {\"c\": 123}}}", map("a", map("b", map("c", 123)))),
        arguments("[[[1, 2]], [[3, 4]]]", asList(asList(asList(1, 2)), asList(asList(3, 4)))),
        arguments("{\"arr\": [1, null, true]}", map("arr", asList(1, null, true))),
        arguments("[{\"x\": 1}, {\"y\": 2}]", asList(map("x", 1), map("y", 2))),
        arguments("[\"a\", {\"k\": [\"v\"]}]", asList("a", map("k", asList("v")))),
        // edge cases
        arguments("\"\"", ""),
        arguments("0", 0),
        arguments("-1", -1),
        arguments("9223372036854775807", Long.MAX_VALUE),
        arguments("1.7976931348623157E308", Double.MAX_VALUE),
        arguments(
            "123456789012345678901234567890", new BigInteger("123456789012345678901234567890")),
        arguments("{\"\": \"empty_key\"}", map("", "empty_key")),
        arguments("{\"null_value\": null}", map("null_value", null)),
        // number variations
        arguments("1.0", 1.0d),
        arguments("-1.5", -1.5d),
        arguments("1e10", 1e10d),
        arguments("1.23e-4", 1.23e-4d),
        // special strings
        arguments("\"\\n\\t\\r\"", "\n\t\r"),
        arguments("\"\\\\\"", "\\"),
        arguments("\"\\\"quotes\\\"\"", "\"quotes\""),
        arguments("\"unicode: \\u0041\"", "unicode: A"));
  }

  @ParameterizedTest
  @MethodSource("convertsJackson3NodesArguments")
  void convertsJackson3Nodes(String json, Object expected) {
    assertEquals(expected, convert(MAPPER.readTree(json), ctx));
  }

  @Test
  void convertsBigDecimalNode() {
    JsonNode node = JsonNodeFactory.instance.numberNode(new BigDecimal("1.50"));
    assertEquals(new BigDecimal("1.50"), convert(node, ctx));
  }

  /** A Jackson 3 node nested in a regular map (e.g. a POJO field) is converted too. */
  @Test
  void convertsNodeNestedInMap() {
    Map<String, Object> body = map("payload", MAPPER.readTree("{\"q\": [\"a\", 1]}"));
    assertEquals(map("payload", map("q", asList("a", 1))), convert(body, ctx));
  }

  @Test
  void truncatesLongStrings() {
    StringBuilder longString = new StringBuilder();
    for (int i = 0; i <= MAX_STRING_SIZE; i++) {
      longString.append('A');
    }

    Object result = convert(MAPPER.readTree("{\"long\": \"" + longString + "\"}"), ctx);

    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(true, false, false);
    assertInstanceOf(Map.class, result);
    assertEquals(longString.substring(0, MAX_STRING_SIZE), ((Map<?, ?>) result).get("long"));
  }

  @Test
  void deepNestingTriggersDepthLimit() {
    String json = "{}";
    for (int i = 1; i <= MAX_DEPTH + 1; i++) {
      json = "{\"child_" + i + "\": " + json + "}";
    }

    Object result = convert(MAPPER.readTree(json), ctx);

    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(false, false, true);
    assertInstanceOf(Map.class, result);
    assertEquals(MAX_DEPTH, countNesting((Map<?, ?>) result));
  }

  @Test
  void largeArrayTriggersElementLimit() {
    StringBuilder json = new StringBuilder("[");
    for (int i = 1; i <= MAX_ELEMENTS + 1; i++) {
      json.append(i > 1 ? "," : "").append(i);
    }
    JsonNode node = MAPPER.readTree(json.append(']').toString());

    Object result = convert(node, ctx);

    verify(ctx).setWafTruncated();
    verify(wafMetricCollector).wafInputTruncated(false, true, false);
    assertInstanceOf(List.class, result);
    List<?> list = (List<?>) result;
    assertTrue(list.size() <= MAX_ELEMENTS);
    assertEquals(asList(1, 2, 3), list.subList(0, 3));
  }

  @Test
  void largeObjectTriggersElementLimit() {
    StringBuilder json = new StringBuilder("{");
    for (int i = 1; i <= MAX_ELEMENTS + 1; i++) {
      json.append(i > 1 ? "," : "").append("\"k").append(i).append("\":").append(i);
    }
    JsonNode node = MAPPER.readTree(json.append('}').toString());

    Object result = convert(node, ctx);

    verify(ctx).setWafTruncated();
    assertInstanceOf(Map.class, result);
    Map<?, ?> map = (Map<?, ?>) result;
    assertTrue(map.size() < MAX_ELEMENTS);
    assertEquals(1, map.get("k1"));
  }

  /**
   * Counts the nested maps below {@code map}, stopping at an empty map or a dropped (null) child.
   */
  private static int countNesting(Map<?, ?> map) {
    int depth = 0;
    Map<?, ?> current = map;
    while (!current.isEmpty()) {
      Object child = current.values().iterator().next();
      if (child == null) {
        break;
      }
      current = (Map<?, ?>) child;
      depth++;
    }
    return depth;
  }

  private static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }
}

package com.datadog.openfeature.internal.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** JSON variants are converted once, at parse time, and shared by every evaluation. */
class FlagEvaluatorJsonValueTest {
  private static final String VARIANT =
      "{\"name\":\"blue\",\"count\":5,\"big\":9007199254740993,\"ratio\":1.5,\"on\":true,"
          + "\"none\":null,\"tags\":[\"a\",\"b\"],\"nested\":{\"items\":[1,{\"n\":2}]}}";

  private final FlagEvaluator evaluator = new FlagEvaluator(false, (f, s, a, v, id) -> false);

  @Test
  void returnsTheSameValueForEachEvaluation() throws Exception {
    final ServerConfiguration config = config(VARIANT);

    final Value first = evaluate(config).getValue();
    final Value second = evaluate(config).getValue();

    assertSame(first, second);
  }

  @Test
  void returnsTheValueThatObjectToValueReturns() throws Exception {
    final Map<String, Object> nested = new HashMap<>();
    nested.put("items", asList(1, singletonMap("n", 2)));
    final Map<String, Object> expected = new HashMap<>();
    expected.put("name", "blue");
    expected.put("count", 5);
    expected.put("big", 9007199254740993L);
    expected.put("ratio", 1.5);
    expected.put("on", true);
    expected.put("none", null);
    expected.put("tags", asList("a", "b"));
    expected.put("nested", nested);

    final Value value = evaluate(config(VARIANT)).getValue();

    assertEquals(Value.objectToValue(expected), value);
    assertEquals(expected, value.asStructure().asObjectMap());
    assertTrue(value.asStructure() instanceof MutableStructure);
  }

  @Test
  void returnsAScalarJsonVariant() throws Exception {
    assertEquals("text", evaluate(config("\"text\"")).getValue().asString());
    assertEquals(5, evaluate(config("5")).getValue().asObject());
  }

  @Test
  void sharesThePlainMapForm() throws Exception {
    final Value value = evaluate(config(VARIANT)).getValue();

    assertSame(value.asStructure().asObjectMap(), value.asStructure().asObjectMap());
  }

  @Test
  void keepsIntegersExact() throws Exception {
    final Value value = evaluate(config(VARIANT)).getValue();
    final Map<String, Object> map = value.asStructure().asObjectMap();

    assertEquals(5, map.get("count"));
    assertEquals(9007199254740993L, map.get("big"));
    assertEquals(1.5, map.get("ratio"));
    assertEquals(5, value.asStructure().getValue("count").asObject());
  }

  @Test
  void cannotBeChangedByTheCaller() throws Exception {
    final Value value = evaluate(config(VARIANT)).getValue();
    final MutableStructure structure = (MutableStructure) value.asStructure();
    final Map<String, Object> map = structure.asObjectMap();
    @SuppressWarnings("unchecked")
    final Map<String, Object> nested = (Map<String, Object>) map.get("nested");
    @SuppressWarnings("unchecked")
    final List<Object> items = (List<Object>) nested.get("items");

    assertThrows(UnsupportedOperationException.class, () -> structure.add("k", "v"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> structure.getValue("tags").asList().add(new Value("c")));
    assertThrows(UnsupportedOperationException.class, () -> map.put("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> nested.put("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> items.add(3));
  }

  @Test
  void keepsTheKeyOrderOfObjectToValue() throws Exception {
    final Map<String, Object> source = new LinkedHashMap<>();
    source.put("b", 1);
    source.put("i", 2);

    final Value value = evaluate(config("{\"b\":1,\"i\":2}")).getValue();

    assertEquals(
        new ArrayList<>(Value.objectToValue(source).asStructure().asObjectMap().keySet()),
        new ArrayList<>(value.asStructure().asObjectMap().keySet()));
    assertEquals(
        new ArrayList<>(Value.objectToValue(source).asStructure().keySet()),
        new ArrayList<>(value.asStructure().keySet()));
  }

  @Test
  void explainsWhyAValueCannotBeChanged() throws Exception {
    final MutableStructure structure =
        (MutableStructure) evaluate(config(VARIANT)).getValue().asStructure();

    final List<Executable> changes =
        asList(
            () -> structure.add("k", new Value("v")),
            () -> structure.add("k", Boolean.TRUE),
            () -> structure.add("k", "v"),
            () -> structure.add("k", 1),
            () -> structure.add("k", 1.5),
            () -> structure.add("k", Instant.EPOCH),
            () -> structure.add("k", (Structure) new MutableStructure()),
            () -> structure.add("k", asList(new Value(1))));

    for (final Executable change : changes) {
      final UnsupportedOperationException error =
          assertThrows(UnsupportedOperationException.class, change);
      assertTrue(error.getMessage().contains("read-only"), error.getMessage());
    }
  }

  @Test
  void returnsAJsonArrayVariant() throws Exception {
    final Value value = evaluate(config("[1,\"two\",{\"three\":3}]")).getValue();

    assertTrue(value.isList());
    assertEquals(1, value.asList().get(0).asObject());
    assertEquals(3, value.asList().get(2).asStructure().getValue("three").asObject());
    assertThrows(UnsupportedOperationException.class, () -> value.asList().add(new Value()));
  }

  @Test
  void keepsDoublesOutsideVariantValues() throws Exception {
    final ServerConfiguration config =
        UniversalFlagConfigParser.parse(
            ("{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,\"variationType\":\"JSON\","
                    + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":{\"n\":1}}},"
                    + "\"allocations\":[{\"key\":\"a\",\"rules\":[{\"conditions\":["
                    + "{\"attribute\":\"age\",\"operator\":\"GTE\",\"value\":18}]}],"
                    + "\"splits\":[{\"variationKey\":\"on\",\"shards\":[]}]}]}}}")
                .getBytes(UTF_8));

    final Object conditionValue =
        config.flags.get("f").allocations.get(0).rules.get(0).conditions.get(0).value;

    assertEquals(18.0, conditionValue);
  }

  @Test
  void keepsIntegersExactForAFlagKeyedVariations() throws Exception {
    final ServerConfiguration config = config("variations", "JSON", "{\"n\":5}");

    final Value value =
        evaluator
            .evaluate(config, Value.class, "variations", new Value(), new MutableContext("user"))
            .getValue();

    assertEquals(5, value.asStructure().asObjectMap().get("n"));
  }

  @Test
  void keepsIntegersExactInTheJsonApiEnvelope() throws Exception {
    final ServerConfiguration config =
        UniversalFlagConfigParser.parseJsonApi(
            ("{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
                    + "{\"createdAt\":\"2024-04-17T19:40:53.716Z\","
                    + "\"environment\":{\"name\":\"Test\"},"
                    + flags("f", "JSON", "{\"big\":9007199254740993}")
                    + "}}}")
                .getBytes(UTF_8));

    assertEquals(
        9007199254740993L, evaluate(config).getValue().asStructure().getValue("big").asObject());
  }

  @Test
  void keepsDoublesInOtherFlagTypes() throws Exception {
    final ServerConfiguration integer = config("f", "INTEGER", "5");
    final ServerConfiguration string = config("f", "STRING", "{\"n\":5,\"l\":[1]}");

    assertEquals(5.0, integer.flags.get("f").variations.get("on").value());
    assertEquals(
        "{n=5.0, l=[1.0]}", String.valueOf(string.flags.get("f").variations.get("on").value()));
    assertFalse(string.flags.get("f").variations.get("on").value() instanceof Value);
  }

  @Test
  void keepsDoublesForIntegersBeyondTheLongRange() throws Exception {
    final Value value = evaluate(config("{\"n\":9223372036854775808}")).getValue();

    assertEquals(9.223372036854776E18, value.asStructure().getValue("n").asObject());
  }

  private ProviderEvaluation<Value> evaluate(final ServerConfiguration config) {
    return evaluator.evaluate(config, Value.class, "f", new Value(), new MutableContext("user"));
  }

  private static ServerConfiguration config(final String variantValue) throws Exception {
    return config("f", "JSON", variantValue);
  }

  private static ServerConfiguration config(
      final String key, final String type, final String variantValue) throws Exception {
    return UniversalFlagConfigParser.parse(
        ("{" + flags(key, type, variantValue) + "}").getBytes(UTF_8));
  }

  private static String flags(final String key, final String type, final String variantValue) {
    return "\"flags\":{\""
        + key
        + "\":{\"key\":\""
        + key
        + "\",\"enabled\":true,\"variationType\":\""
        + type
        + "\",\"variations\":{\"on\":{\"key\":\"on\",\"value\":"
        + variantValue
        + "}},\"allocations\":[{\"key\":\"a\",\"splits\":[{\"variationKey\":\"on\","
        + "\"shards\":[]}]}]}}";
  }
}

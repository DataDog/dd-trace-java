package com.datadog.openfeature.internal.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
    final ServerConfiguration config = config(VARIANT);
    final Object tree = config.flags.get("f").variations.get("on").value;

    final Value value = evaluate(config).getValue();

    assertEquals(Value.objectToValue(tree), value);
    assertEquals(
        Value.objectToValue(tree).asStructure().asObjectMap(), value.asStructure().asObjectMap());
    assertTrue(value.asStructure() instanceof MutableStructure);
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

  private ProviderEvaluation<Value> evaluate(final ServerConfiguration config) {
    return evaluator.evaluate(config, Value.class, "f", new Value(), new MutableContext("user"));
  }

  private static ServerConfiguration config(final String variantValue) throws Exception {
    return UniversalFlagConfigParser.parse(
        ("{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,\"variationType\":\"JSON\","
                + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":"
                + variantValue
                + "}},"
                + "\"allocations\":[{\"key\":\"a\",\"splits\":[{\"variationKey\":\"on\","
                + "\"shards\":[]}]}]}}}")
            .getBytes(UTF_8));
  }
}

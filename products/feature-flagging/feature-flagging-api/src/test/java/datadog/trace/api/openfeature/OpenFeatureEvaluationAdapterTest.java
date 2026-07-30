package datadog.trace.api.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Environment;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import datadog.trace.api.featureflag.ufc.v1.ShardRange;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenFeatureEvaluationAdapterTest {

  @Test
  void mapsEvaluationMetadataContextAndExposure() {
    final AtomicInteger exposures = new AtomicInteger();
    final OpenFeatureEvaluationAdapter adapter =
        new OpenFeatureEvaluationAdapter(
            (flagKey, allocationKey, variantKey, context) -> exposures.incrementAndGet(), true);
    final Rule rule =
        new Rule(
            singletonList(
                new ConditionConfiguration(ConditionOperator.ONE_OF, "id", singletonList("US"))));
    final ProviderEvaluation<String> result =
        adapter.evaluate(
            configuration(ValueType.STRING, "hello", true, singletonList(rule), emptyList()),
            String.class,
            "flag",
            "default",
            new MutableContext("subject").add("id", "US"));

    assertEquals("hello", result.getValue());
    assertEquals("TARGETING_MATCH", result.getReason());
    assertEquals("on", result.getVariant());
    assertEquals("flag", result.getFlagMetadata().getString("flagKey"));
    assertEquals("STRING", result.getFlagMetadata().getString("variationType"));
    assertEquals("allocation", result.getFlagMetadata().getString("allocationKey"));
    assertEquals(7, result.getFlagMetadata().getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
    assertEquals(true, result.getFlagMetadata().getBoolean(DDEvaluator.METADATA_DO_LOG));
    assertEquals(1, exposures.get());
  }

  @Test
  void mapsCoreErrorsToOpenFeatureErrors() {
    final OpenFeatureEvaluationAdapter adapter = new OpenFeatureEvaluationAdapter(null, false);
    final MutableContext context = new MutableContext("subject");

    assertError(
        ErrorCode.FLAG_NOT_FOUND,
        adapter.evaluate(
            new ServerConfiguration(null, "SERVER", new Environment("test"), emptyMap()),
            String.class,
            "flag",
            "default",
            context));
    assertError(
        ErrorCode.TYPE_MISMATCH,
        adapter.evaluate(
            configuration(ValueType.STRING, "hello", true, emptyList(), emptyList()),
            Boolean.class,
            "flag",
            false,
            context));
    assertError(
        ErrorCode.PARSE_ERROR,
        adapter.evaluate(
            configuration(ValueType.INTEGER, "not-an-integer", true, emptyList(), emptyList()),
            Integer.class,
            "flag",
            1,
            context));
    assertError(
        ErrorCode.TARGETING_KEY_MISSING,
        adapter.evaluate(
            configuration(
                ValueType.STRING,
                "hello",
                true,
                emptyList(),
                singletonList(new Shard("salt", singletonList(new ShardRange(0, 1)), 1))),
            String.class,
            "flag",
            "default",
            new MutableContext()));
  }

  @Test
  void preservesObjectDefaultsAndUnwrapsOpenFeatureValues() {
    final OpenFeatureEvaluationAdapter adapter = new OpenFeatureEvaluationAdapter(null, false);
    final Value defaultValue = Value.objectToValue(singletonMap("default", true));
    final ProviderEvaluation<Value> disabled =
        adapter.evaluate(
            configuration(
                ValueType.JSON, singletonMap("enabled", true), false, emptyList(), emptyList()),
            Value.class,
            "flag",
            defaultValue,
            new MutableContext("subject"));
    assertSame(defaultValue, disabled.getValue());

    final ProviderEvaluation<Value> enabled =
        adapter.evaluate(
            configuration(
                ValueType.JSON, singletonMap("enabled", true), true, emptyList(), emptyList()),
            Value.class,
            "flag",
            new Value(),
            new MutableContext("subject"));
    assertEquals(Value.objectToValue(singletonMap("enabled", true)), enabled.getValue());

    final Instant instant = Instant.parse("2026-01-01T00:00:00Z");
    final Map<String, Object> object = new LinkedHashMap<>();
    object.put("null", null);
    object.put("boolean", true);
    object.put("string", "value");
    object.put("integer", 1);
    object.put("double", 1.5);
    object.put("list", Arrays.asList("value", 2));
    object.put("instant", instant);

    final Map<?, ?> unwrapped =
        (Map<?, ?>) OpenFeatureEvaluationAdapter.unwrapDefaultValue(Value.objectToValue(object));
    assertNull(unwrapped.get("null"));
    assertEquals(true, unwrapped.get("boolean"));
    assertEquals("value", unwrapped.get("string"));
    assertEquals(1, unwrapped.get("integer"));
    assertEquals(1.5, unwrapped.get("double"));
    assertEquals(Arrays.asList("value", 2), unwrapped.get("list"));
    assertEquals(instant.toString(), unwrapped.get("instant"));
    assertNull(OpenFeatureEvaluationAdapter.unwrapDefaultValue(new Value()));
    assertEquals("raw", OpenFeatureEvaluationAdapter.unwrapDefaultValue("raw"));
  }

  private static void assertError(
      final ErrorCode expected, final ProviderEvaluation<?> evaluation) {
    assertEquals(expected, evaluation.getErrorCode());
  }

  private static ServerConfiguration configuration(
      final ValueType type,
      final Object value,
      final boolean enabled,
      final java.util.List<Rule> rules,
      final java.util.List<Shard> shards) {
    final Split split = new Split(shards, "on", emptyMap(), 7);
    final Allocation allocation =
        new Allocation("allocation", rules, null, null, singletonList(split), true);
    final Flag flag =
        new Flag(
            "flag",
            enabled,
            type,
            singletonMap("on", new Variant("on", value)),
            singletonList(allocation));
    return new ServerConfiguration(
        null, "SERVER", new Environment("test"), singletonMap("flag", flag));
  }
}

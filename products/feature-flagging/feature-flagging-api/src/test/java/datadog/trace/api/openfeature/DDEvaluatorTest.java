package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.FeatureFlaggingRawBridge;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DDEvaluatorTest {

  private DDEvaluator evaluator;

  @AfterEach
  void close() {
    if (evaluator != null) {
      evaluator.shutdown();
    }
    FeatureFlaggingRawBridge.dispatchConfiguration(null);
  }

  @Test
  void initializesFromLateRemoteConfigurationListener() throws Exception {
    FeatureFlaggingRawBridge.dispatchConfiguration(UFC.getBytes(UTF_8));
    final AtomicBoolean changed = new AtomicBoolean();
    evaluator =
        new DDEvaluator(
            () -> changed.set(true), new Provider.Options().configurationSource("remote_config"));

    assertTrue(evaluator.initialize(1, SECONDS, new MutableContext("subject")));
    assertTrue(evaluator.hasConfiguration());
    assertTrue(changed.get());
  }

  @Test
  void evaluatesProviderOwnedConfiguration() throws Exception {
    FeatureFlaggingRawBridge.dispatchConfiguration(UFC.getBytes(UTF_8));
    evaluator =
        new DDEvaluator(() -> {}, new Provider.Options().configurationSource("remote_config"));
    evaluator.initialize(1, SECONDS, new MutableContext("subject"));

    final ProviderEvaluation<String> result =
        evaluator.evaluate(String.class, "message", "default", new MutableContext("subject"));

    assertEquals("hello", result.getValue());
    assertEquals("STATIC", result.getReason());
    assertEquals("on", result.getVariant());
    assertEquals("allocation", result.getFlagMetadata().getString("allocationKey"));
  }

  @Test
  void reportsProviderNotReadyWithoutInitialization() {
    evaluator =
        new DDEvaluator(() -> {}, new Provider.Options().configurationSource("remote_config"));

    final ProviderEvaluation<Integer> result =
        evaluator.evaluate(Integer.class, "message", 23, new MutableContext("subject"));

    assertEquals(23, result.getValue());
    assertEquals(ErrorCode.PROVIDER_NOT_READY, result.getErrorCode());
  }

  @Test
  void preservesObjectDefaultsForFallbackResults() throws Exception {
    FeatureFlaggingRawBridge.dispatchConfiguration(OBJECT_FALLBACK_UFC.getBytes(UTF_8));
    evaluator =
        new DDEvaluator(() -> {}, new Provider.Options().configurationSource("remote_config"));
    evaluator.initialize(1, SECONDS, new MutableContext("subject"));
    final MutableContext context = new MutableContext("subject");
    final Value instantDefault = new Value(Instant.parse("2026-07-29T00:00:00Z"));
    final Value doubleDefault = new Value(42D);

    final ProviderEvaluation<Value> disabled =
        evaluator.evaluate(Value.class, "disabled", instantDefault, context);
    final ProviderEvaluation<Value> unmatched =
        evaluator.evaluate(Value.class, "unmatched", doubleDefault, context);

    assertEquals("DISABLED", disabled.getReason());
    assertSame(instantDefault, disabled.getValue());
    assertEquals("DEFAULT", unmatched.getReason());
    assertSame(doubleDefault, unmatched.getValue());
  }

  @Test
  void mapsSupportedValues() {
    assertEquals("42", DDEvaluator.mapValue(String.class, 42));
    assertEquals(42, DDEvaluator.mapValue(Integer.class, "42"));
    assertEquals(42D, DDEvaluator.mapValue(Double.class, 42));
    assertEquals(Value.objectToValue("value"), DDEvaluator.mapValue(Value.class, "value"));
    assertThrows(IllegalArgumentException.class, () -> DDEvaluator.mapValue(Date.class, "date"));
  }

  @Test
  void flattensNestedContextForPrimitiveTelemetryBridge() {
    final MutableContext context =
        new MutableContext(
            Value.objectToValue(Map.of("nested", Map.of("value", 7))).asStructure().asMap());

    assertEquals(7, DDEvaluator.flattenContext(context).get("nested.value"));
  }

  private static final String UFC =
      "{\"format\":\"SERVER\",\"environment\":{\"name\":\"test\"},\"flags\":{"
          + "\"message\":{\"key\":\"message\",\"enabled\":true,\"variationType\":\"STRING\","
          + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"hello\"}},"
          + "\"allocations\":[{\"key\":\"allocation\",\"rules\":[],\"splits\":["
          + "{\"variationKey\":\"on\",\"shards\":[],\"serialId\":7}],\"doLog\":true}]}}}";

  private static final String OBJECT_FALLBACK_UFC =
      "{\"format\":\"SERVER\",\"environment\":{\"name\":\"test\"},\"flags\":{"
          + "\"disabled\":{\"key\":\"disabled\",\"enabled\":false,\"variationType\":\"JSON\","
          + "\"variations\":{},\"allocations\":[]},"
          + "\"unmatched\":{\"key\":\"unmatched\",\"enabled\":true,\"variationType\":\"JSON\","
          + "\"variations\":{},\"allocations\":[]}}}";
}

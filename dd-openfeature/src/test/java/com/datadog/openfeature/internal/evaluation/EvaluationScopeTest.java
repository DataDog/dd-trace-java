package com.datadog.openfeature.internal.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationScopeTest {

  @Test
  void resolvesEachAttributeOnce() {
    final CountingContext context = new CountingContext("user-1");
    context.add("plan", "pro");
    final EvaluationScope scope = new EvaluationScope(context);

    assertEquals("pro", scope.attribute("plan"));
    assertEquals("pro", scope.attribute(new String("plan")));
    assertNull(scope.attribute("missing"));
    assertNull(scope.attribute("missing"));

    assertEquals(1, context.reads("plan"));
    assertEquals(1, context.reads("missing"));
  }

  @Test
  void resolvesManyAttributesOnce() {
    final CountingContext context = new CountingContext("user-1");
    for (int i = 0; i < 20; i++) {
      context.add("attr" + i, i);
    }
    final EvaluationScope scope = new EvaluationScope(context);

    for (int round = 0; round < 2; round++) {
      for (int i = 0; i < 20; i++) {
        assertEquals(i, scope.attribute("attr" + i));
      }
      assertNull(scope.attribute("missing"));
    }

    for (int i = 0; i < 20; i++) {
      assertEquals(1, context.reads("attr" + i));
    }
    assertEquals(1, context.reads("missing"));
  }

  @Test
  void fallsBackToTheTargetingKeyForAnAbsentId() {
    assertEquals("user-1", new EvaluationScope(new MutableContext("user-1")).attribute("id"));

    final MutableContext context = new MutableContext("user-1");
    context.add("id", "explicit");
    assertEquals("explicit", new EvaluationScope(context).attribute("id"));
  }

  @Test
  void keepsAnExplicitNullIdWithoutFallingBackToTheTargetingKey() {
    final Map<String, Value> attributes = new HashMap<>();
    attributes.put("id", new Value());
    final EvaluationScope scope = new EvaluationScope(new MutableContext("user-1", attributes));

    assertNull(scope.attribute("id"));
    assertNull(scope.attribute("id"));
  }

  @Test
  void fallsBackToTheTargetingKeyForARawNullId() {
    // The context's keys are not read, so a raw Java null cannot be told apart from no entry.
    final Map<String, Value> attributes = new HashMap<>();
    attributes.put("id", null);
    final EvaluationScope scope = new EvaluationScope(new MutableContext("user-1", attributes));

    assertEquals("user-1", scope.attribute("id"));
  }

  /** Counts the attribute reads of the evaluation. */
  private static final class CountingContext extends MutableContext {
    private final Map<String, Integer> reads = new HashMap<>();

    CountingContext(final String targetingKey) {
      super(targetingKey);
    }

    @Override
    public Value getValue(final String key) {
      reads.merge(key, 1, Integer::sum);
      return super.getValue(key);
    }

    int reads(final String key) {
      return reads.getOrDefault(key, 0);
    }
  }
}

package com.datadog.openfeature.internal.flagevaluation;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagEvalEventTest {
  @Test
  void storesFeaturesAndDefaultsThemToEmpty() {
    final Map<String, Object> features = singletonMap("bandit.policy_id", "p2");

    assertSame(
        features,
        new FlagEvalEvent("f", "on", "a", "t", null, 1L, false, emptyMap(), features).features);
    assertTrue(
        new FlagEvalEvent("f", "on", "a", "t", null, 1L, false, emptyMap(), null)
            .features.isEmpty());
    assertTrue(new FlagEvalEvent("f", "on", "a", "t", 1L, emptyMap()).features.isEmpty());
  }
}

package datadog.trace.api.featureflag.flagevaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagEvalEventTest {

  @Test
  void storesFieldsWithContextAttributes() {
    final Map<String, Object> attrs = Collections.singletonMap("tier", "gold");

    final FlagEvalEvent event =
        new FlagEvalEvent("my-flag", "on", "allocation-1", "target-1", 123L, attrs);

    assertEquals("my-flag", event.flagKey);
    assertEquals("on", event.variant);
    assertEquals("allocation-1", event.allocationKey);
    assertEquals("target-1", event.targetingKey);
    assertNull(event.errorMessage);
    assertEquals(123L, event.evalTimeMs);
    assertSame(attrs, event.attrs);
    assertTrue(event.estimatedRetainedBytes > 0);
  }

  @Test
  void storesErrorMessageAndDefaultsNullContextAttributes() {
    final Map<String, Object> attrs = null;
    final FlagEvalEvent event =
        new FlagEvalEvent("my-flag", null, null, null, "type mismatch", 456L, attrs);

    assertEquals("type mismatch", event.errorMessage);
    assertTrue(event.attrs.isEmpty());
  }

  @Test
  void observeFullEvaluationDataDefaultsToFalseOnConvenienceConstructors() {
    final Map<String, Object> attrs = Collections.emptyMap();
    assertFalse(new FlagEvalEvent("f", "on", "a", "t", 1L, attrs).observeFullEvaluationData);
    assertFalse(new FlagEvalEvent("f", "on", "a", "t", null, 1L, attrs).observeFullEvaluationData);
  }

  @Test
  void storesExplicitObserveFullEvaluationData() {
    final Map<String, Object> attrs = Collections.emptyMap();
    assertTrue(
        new FlagEvalEvent("f", "on", "a", "t", null, 1L, true, attrs).observeFullEvaluationData);
  }

  @Test
  void precomputedContextBytesMatchFallbackEstimate() {
    final Map<String, Object> attrs = Collections.singletonMap("tier", "gold");
    final long retainedBytes = FlagEvalEventMemoryEstimator.retainedContextBytes(attrs);

    final FlagEvalEvent event =
        new FlagEvalEvent("f", "on", "a", "t", null, 1L, true, attrs, retainedBytes);
    final FlagEvalEvent fallback = new FlagEvalEvent("f", "on", "a", "t", null, 1L, true, attrs);

    assertEquals(fallback.estimatedRetainedBytes, event.estimatedRetainedBytes);
  }
}

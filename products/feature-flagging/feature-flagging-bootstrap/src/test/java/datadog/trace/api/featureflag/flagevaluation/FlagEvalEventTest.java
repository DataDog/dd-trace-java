package datadog.trace.api.featureflag.flagevaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FlagEvalEventTest {

  @Test
  void storesFieldsWithEagerContextAttributes() {
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
    assertSame(attrs, event.contextAttributes());
  }

  @Test
  void storesErrorMessageAndDefaultsNullEagerContextAttributes() {
    final Map<String, Object> attrs = null;
    final FlagEvalEvent event =
        new FlagEvalEvent("my-flag", null, null, null, "type mismatch", 456L, attrs);

    assertEquals("type mismatch", event.errorMessage);
    assertTrue(event.attrs.isEmpty());
    assertTrue(event.contextAttributes().isEmpty());
  }

  @Test
  void resolvesLazyContextAttributesOnDemand() {
    final AtomicInteger resolutions = new AtomicInteger();
    final Map<String, Object> attrs = Collections.singletonMap("region", "us-east-1");
    final FlagEvalEvent event =
        new FlagEvalEvent(
            "my-flag",
            "on",
            "allocation-1",
            "target-1",
            null,
            789L,
            () -> {
              resolutions.incrementAndGet();
              return attrs;
            });

    assertTrue(event.attrs.isEmpty());
    assertEquals(0, resolutions.get());
    assertSame(attrs, event.contextAttributes());
    assertEquals(1, resolutions.get());
  }

  @Test
  void defaultsNullLazyContextAttributes() {
    final FlagEvalEvent event =
        new FlagEvalEvent("my-flag", "on", null, null, null, 789L, () -> null);

    assertTrue(event.contextAttributes().isEmpty());
  }

  @Test
  void observeFullEvaluationDataDefaultsToFalseOnConvenienceConstructors() {
    final Map<String, Object> attrs = Collections.emptyMap();
    assertFalse(new FlagEvalEvent("f", "on", "a", "t", 1L, attrs).observeFullEvaluationData);
    assertFalse(new FlagEvalEvent("f", "on", "a", "t", null, 1L, attrs).observeFullEvaluationData);
    assertFalse(
        new FlagEvalEvent("f", "on", "a", "t", null, 1L, () -> attrs).observeFullEvaluationData);
  }

  @Test
  void storesExplicitObserveFullEvaluationData() {
    final Map<String, Object> attrs = Collections.emptyMap();
    assertTrue(
        new FlagEvalEvent("f", "on", "a", "t", null, 1L, true, attrs).observeFullEvaluationData);
    assertTrue(
        new FlagEvalEvent("f", "on", "a", "t", null, 1L, true, () -> attrs)
            .observeFullEvaluationData);
  }
}

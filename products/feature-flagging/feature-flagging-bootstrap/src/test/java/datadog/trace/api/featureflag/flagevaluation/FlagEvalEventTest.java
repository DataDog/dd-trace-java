package datadog.trace.api.featureflag.flagevaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  }

  @Test
  void storesErrorMessageAndDefaultsNullContextAttributes() {
    final Map<String, Object> attrs = null;
    final FlagEvalEvent event =
        new FlagEvalEvent("my-flag", null, null, null, "type mismatch", 456L, attrs);

    assertEquals("type mismatch", event.errorMessage);
    assertTrue(event.attrs.isEmpty());
  }
}

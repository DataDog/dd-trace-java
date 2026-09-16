package datadog.trace.api.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;

class SpanEnrichmentEventTest {

  @Test
  void serialIdEventCarriesItsFields() {
    final SpanEnrichmentEvent event = SpanEnrichmentEvent.serialId(42, true, "user-1");

    assertTrue(event.hasSerialId());
    assertEquals(42, event.serialId());
    assertTrue(event.doLog());
    assertEquals("user-1", event.targetingKey());
    assertNull(event.flagKey());
    assertNull(event.defaultValue());
  }

  @Test
  void serialIdEventWithoutDoLogOrTargetingKey() {
    final SpanEnrichmentEvent event = SpanEnrichmentEvent.serialId(7, false, null);

    assertTrue(event.hasSerialId());
    assertEquals(7, event.serialId());
    assertFalse(event.doLog());
    assertNull(event.targetingKey());
  }

  @Test
  void runtimeDefaultEventCarriesItsFields() {
    final Object value = Collections.singletonMap("k", "v");
    final SpanEnrichmentEvent event = SpanEnrichmentEvent.runtimeDefault("flag", value);

    assertFalse(event.hasSerialId());
    assertEquals("flag", event.flagKey());
    assertEquals(value, event.defaultValue());
    assertEquals(0, event.serialId());
    assertFalse(event.doLog());
    assertNull(event.targetingKey());
  }

  @Test
  void runtimeDefaultEventAllowsNullValue() {
    final SpanEnrichmentEvent event = SpanEnrichmentEvent.runtimeDefault("flag", null);

    assertFalse(event.hasSerialId());
    assertEquals("flag", event.flagKey());
    assertNull(event.defaultValue());
  }
}

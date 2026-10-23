package datadog.trace.test.agent.decoder;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanEventsDecoderTest {
  // The decoder keeps the tag name private, so spell it out the way the tracer writes it.
  private static final String SPAN_EVENTS_TAG = "events";

  @Test
  void absentOrEmptyTagYieldsNoEvent() {
    assertTrue(DecodedSpanEvents.fromMeta(emptyMap()).isEmpty(), "no tag in meta");
    assertTrue(fromTag(null).isEmpty(), "null tag");
    assertTrue(fromTag("").isEmpty(), "empty tag");
    assertTrue(fromTag("[]").isEmpty(), "empty array");
    assertTrue(fromTag("null").isEmpty(), "JSON null");
  }

  @Test
  void decodesEventWithoutAttributes() {
    List<DecodedSpanEvent> events = fromTag("[{\"time_unix_nano\":1234,\"name\":\"event\"}]");

    assertEquals(1, events.size());
    DecodedSpanEvent event = events.get(0);
    assertEquals("event", event.getName());
    assertEquals(1234L, event.getTimeUnixNano());
    assertTrue(event.getAttributes().isEmpty(), "absent attributes default to empty");
  }

  @Test
  void decodesTypedAttributes() {
    List<DecodedSpanEvent> events =
        fromTag(
            "[{\"time_unix_nano\":1,\"name\":\"event\",\"attributes\":{"
                + "\"str\":\"value\",\"int\":42,\"double\":12.5,\"whole\":2.0,\"exp\":1e3,"
                + "\"bool\":true,\"arr\":[\"x\",7,2.5,false],\"unsupported\":null}}]");

    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("str", "value");
    expected.put("int", 42L);
    expected.put("double", 12.5d);
    expected.put("whole", 2.0d);
    expected.put("exp", 1000d);
    expected.put("bool", true);
    expected.put("arr", asList("x", 7L, 2.5d, false));
    expected.put("unsupported", null);
    assertEquals(expected, events.get(0).getAttributes());
  }

  @Test
  void preservesEventOrderAndIgnoresUnknownFields() {
    List<DecodedSpanEvent> events =
        fromTag(
            "[{\"time_unix_nano\":1,\"name\":\"first\",\"extra\":{\"a\":1}},"
                + "{\"name\":\"second\",\"time_unix_nano\":2}]");

    assertEquals(2, events.size());
    assertEquals("first", events.get(0).getName());
    assertEquals("second", events.get(1).getName());
    assertEquals(2L, events.get(1).getTimeUnixNano());
  }

  @Test
  void rejectsMalformedEvents() {
    assertThrows(IllegalStateException.class, () -> fromTag("not json"), "not JSON");
    assertThrows(IllegalStateException.class, () -> fromTag("[1]"), "non-object event");
    assertThrows(IllegalStateException.class, () -> fromTag("[]garbage"), "trailing garbage");
    assertThrows(IllegalStateException.class, () -> fromTag("[][]"), "concatenated arrays");
    assertThrows(IllegalStateException.class, () -> fromTag("null x"), "trailing data after null");
    assertThrows(
        IllegalStateException.class, () -> fromTag("[{\"name\":\"event\"}]"), "missing time");
    assertThrows(
        IllegalStateException.class, () -> fromTag("[{\"time_unix_nano\":1}]"), "missing name");
    assertThrows(
        IllegalStateException.class,
        () ->
            fromTag(
                "[{\"time_unix_nano\":1,\"name\":\"event\","
                    + "\"attributes\":{\"big\":9223372036854775808}}]"),
        "integer beyond the long range");
  }

  @Test
  void buildsStructuredEvent() {
    DecodedSpanEvent event = DecodedSpanEvents.event("event", 1L, singletonMap("key", "value"));

    assertEquals("event", event.getName());
    assertEquals(1L, event.getTimeUnixNano());
    assertEquals(singletonMap("key", "value"), event.getAttributes());
    assertTrue(DecodedSpanEvents.event("event", 1L, null).getAttributes().isEmpty());
    assertTrue(event.toString().contains("name='event'"));
  }

  private static List<DecodedSpanEvent> fromTag(String tagValue) {
    return DecodedSpanEvents.fromMeta(singletonMap(SPAN_EVENTS_TAG, tagValue));
  }
}

package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.SAMPLED_FLAG;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpanLinkTest {

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testSpanLinkFromContext(boolean sampled) {
    DDTraceId traceId = DDTraceId.fromHex("11223344556677889900aabbccddeeff");
    long spanId = DDSpanId.fromHex("123456789abcdef0");
    AgentSpanContext context = mock(AgentSpanContext.class);
    when(context.getTraceId()).thenReturn(traceId);
    when(context.getSpanId()).thenReturn(spanId);
    when(context.getSamplingPriority()).thenReturn(sampled ? 1 : 0);

    SpanLink link = SpanLink.from(context);

    assertEquals(traceId, link.traceId());
    assertEquals(spanId, link.spanId());
    assertEquals(sampled ? SAMPLED_FLAG : DEFAULT_FLAGS, link.traceFlags());
    assertEquals("", link.traceState());
    assertEquals(SpanAttributes.EMPTY, link.attributes());

    assertDoesNotThrow(link::toString);
  }

  @Test
  void testSpanLinksApi() {
    SpanLink link = new SpanLink(null, 0L, (byte) 0, null, null);

    assertEquals(DDTraceId.ZERO, link.traceId());
    assertNotNull(link.traceState());
    assertTrue(link.traceState().isEmpty());
    assertNotNull(link.attributes());
    assertTrue(link.attributes().isEmpty());
  }

  @Test
  void testSpanLinkAttributesApi() {
    SpanAttributes attributes = SpanAttributes.builder().build();

    assertTrue(attributes.isEmpty());

    attributes = SpanAttributes.builder().put("key", "value").build();

    assertFalse(attributes.isEmpty());
  }

  @Test
  void testSpanLinkAttributesEncoding() {
    SpanAttributes.Builder builder = SpanAttributes.builder();

    builder.put("string", "value");
    builder.put("string-empty", "");
    builder.put("string-null", (String) null);
    builder.put("bool", true);
    builder.put("bool-false", false);
    builder.put("long", 12345L);
    builder.put("long-negative", -12345L);
    builder.put("double", 67.89);
    builder.put("double-negative", -67.89);
    builder.putStringArray("string-array", asList("abc", "", null, "def"));
    builder.putStringArray("string-array-null", null);
    builder.putBooleanArray("bool-array", asList(true, false, null, Boolean.TRUE, Boolean.FALSE));
    builder.putStringArray("bool-array-null", null);
    builder.putLongArray("long-array", asList(123L, 456L, null, Long.MIN_VALUE, Long.MAX_VALUE));
    builder.putStringArray("long-array-null", null);
    builder.putDoubleArray(
        "double-array", asList(12.3D, 45.6D, null, Double.MIN_VALUE, Double.MAX_VALUE));
    builder.putStringArray("double-array-null", null);
    Map<String, String> map = builder.build().asMap();

    assertEquals("value", map.get("string"));
    assertEquals("", map.get("string-empty"));
    assertFalse(map.containsKey("string-null"));
    assertEquals("true", map.get("bool"));
    assertEquals("false", map.get("bool-false"));
    assertEquals("12345", map.get("long"));
    assertEquals("-12345", map.get("long-negative"));
    assertEquals("67.89", map.get("double"));
    assertEquals("-67.89", map.get("double-negative"));

    assertEquals("abc", map.get("string-array.0"));
    assertEquals("", map.get("string-array.1"));
    assertFalse(map.containsKey("string-array.2"));
    assertEquals("def", map.get("string-array.3"));
    assertFalse(map.containsKey("string-array-null"));

    assertEquals("true", map.get("bool-array.0"));
    assertEquals("false", map.get("bool-array.1"));
    assertFalse(map.containsKey("bool-array.2"));
    assertEquals("true", map.get("bool-array.3"));
    assertEquals("false", map.get("bool-array.4"));
    assertFalse(map.containsKey("bool-array-null"));

    assertEquals("123", map.get("long-array.0"));
    assertEquals("456", map.get("long-array.1"));
    assertFalse(map.containsKey("long-array.2"));
    assertEquals("-9223372036854775808", map.get("long-array.3"));
    assertEquals("9223372036854775807", map.get("long-array.4"));
    assertFalse(map.containsKey("long-array-null"));

    assertEquals("12.3", map.get("double-array.0"));
    assertEquals("45.6", map.get("double-array.1"));
    assertFalse(map.containsKey("double-array.2"));
    // Field should be encoded as String using JSON representation so the scientific notation is
    // valid
    assertEquals("4.9E-324", map.get("double-array.3"));
    assertEquals("1.7976931348623157E308", map.get("double-array.4"));
    assertFalse(map.containsKey("double-array-null"));

    SpanAttributes attributes = SpanAttributes.fromMap(map);

    assertEquals(map, attributes.asMap());
  }

  @Test
  void testSpanLinkAttributesToString() {
    assertDoesNotThrow(() -> SpanAttributes.builder().build().toString());
  }

  @Test
  void testSpanLinkAttributesEqualsAndHashcode() {
    SpanAttributes attributes = SpanAttributes.builder().put("test", "value").build();
    SpanAttributes sameAttributes = SpanAttributes.builder().put("test", "value").build();
    SpanAttributes emptyAttributes = SpanAttributes.builder().build();

    assertEquals(attributes, sameAttributes);
    assertNotEquals(attributes, emptyAttributes);
    // argument order matters: exercises emptyAttributes.equals(null)
    assertNotEquals(emptyAttributes, null);
    assertEquals(attributes.hashCode(), sameAttributes.hashCode());
    assertNotEquals(attributes.hashCode(), emptyAttributes.hashCode());
  }
}

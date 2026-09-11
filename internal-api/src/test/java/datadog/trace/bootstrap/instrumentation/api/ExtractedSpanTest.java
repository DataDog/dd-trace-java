package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.TraceConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtractedSpanTest {

  @Test
  void extractedSpanFromPartialTracingContext() {
    Map<String, String> tagValues = new HashMap<>();
    tagValues.put("tag-1", "value-1");
    tagValues.put("tag-2", "value-2");
    TagMap tags = TagMap.fromMap(tagValues);
    Map<String, String> baggage = new HashMap<>();
    baggage.put("baggage-1", "value-1");
    baggage.put("baggage-2", "value-2");
    DDTraceId traceId = DDTraceId.from(12345);
    TagContext context = new TagContext("origin", tags, null, baggage, 0, null, null, traceId);
    ExtractedSpan extractedSpan = new ExtractedSpan(context);

    assertEquals(traceId, extractedSpan.getTraceId());
    assertEquals(context.getSpanId(), extractedSpan.getSpanId());
    assertEquals(context, extractedSpan.spanContext());
    assertEquals(tags, extractedSpan.getTags());
    assertEquals("value-1", extractedSpan.getTag("tag-1"));
    assertEquals("value-2", extractedSpan.getBaggageItem("baggage-2"));
    assertTrue(extractedSpan.isSameTrace(new ExtractedSpan(context)));
    assertNotNull(extractedSpan.toString());

    extractedSpan.setTag("tag-1", "updated");
    extractedSpan.setBaggageItem("baggage-2", "updated");

    assertEquals("value-1", extractedSpan.getTag("tag-1"));
    assertEquals("value-2", extractedSpan.getBaggageItem("baggage-2"));
  }

  @Test
  void extractedSpanFromCustomSpanContext() {
    AgentSpanContext context = mock(AgentSpanContext.class);
    when(context.getTraceId()).thenReturn(DDTraceId.from(12345));
    when(context.getSpanId()).thenReturn(67890L);
    when(context.baggageItems()).thenReturn(Collections.<String, String>emptyMap().entrySet());
    ExtractedSpan extractedSpan = new ExtractedSpan(context);

    assertEquals(context.getTraceId(), extractedSpan.getTraceId());
    assertEquals(context.getSpanId(), extractedSpan.getSpanId());
    assertEquals(context, extractedSpan.spanContext());
    assertTrue(extractedSpan.getTags().isEmpty());
    assertNull(extractedSpan.getTag("tag-1"));
    assertNull(extractedSpan.getBaggageItem("baggage-2"));
    assertTrue(extractedSpan.isSameTrace(new ExtractedSpan(context)));
    assertNotNull(extractedSpan.toString());
  }

  @Test
  void traceConfigReturnsExtractedSnapshotWhenPresent() {
    TraceConfig snapshot = mock(TraceConfig.class);
    TagContext context = new TagContext(null, null, null, null, 0, snapshot, null, DDTraceId.ZERO);
    ExtractedSpan extractedSpan = new ExtractedSpan(context);

    assertEquals(snapshot, extractedSpan.traceConfig());
  }

  @Test
  void traceConfigFallsBackToCurrentConfigWhenSnapshotAbsent() {
    TagContext context = new TagContext();
    ExtractedSpan extractedSpan = new ExtractedSpan(context);

    assertNotNull(extractedSpan.traceConfig());
  }

  @Test
  void traceConfigFallsBackToCurrentConfigForCustomSpanContext() {
    AgentSpanContext context = mock(AgentSpanContext.class);
    ExtractedSpan extractedSpan = new ExtractedSpan(context);

    assertNotNull(extractedSpan.traceConfig());
  }
}

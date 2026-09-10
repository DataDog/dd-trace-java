package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static datadog.trace.core.otlp.common.OtlpCommonJson.hexSpanId;
import static datadog.trace.core.otlp.common.OtlpCommonJson.hexTraceId;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.SAMPLED_TRACE_FLAG;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.json.JsonMapper;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OtlpTraceJsonCollector}, parsing the produced JSON back with {@link JsonMapper}
 * and asserting against the OTLP JSON encoding (hex ids, camelCase keys, integer {@code kind},
 * decimal-string timestamps).
 */
class OtlpTraceJsonCollectorTest {

  private static final CoreTracer TRACER = CoreTracer.builder().writer(new LoggingWriter()).build();

  @Test
  void emptyTraceProducesEmptyPayload() {
    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    OtlpPayload payload = collector.collectTraces();
    assertEquals(OtlpPayload.EMPTY, payload);
  }

  @Test
  void singleSpanIsEncodedWithHexIdsAndCamelCaseKeys() throws IOException {
    DDSpan span = startAndFinish("op.first", "GET /api/users", null);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    OtlpPayload payload = collector.collectTraces();

    Map<String, Object> parsedSpan = onlySpan(payload);

    assertEquals(hexTraceId(span.getTraceId()), parsedSpan.get("traceId"));
    assertEquals(hexSpanId(span.getSpanId()), parsedSpan.get("spanId"));
    assertEquals("GET /api/users", parsedSpan.get("name"));
    assertTrue(parsedSpan.get("startTimeUnixNano") instanceof String, "timestamps are strings");
    assertTrue(parsedSpan.get("endTimeUnixNano") instanceof String, "timestamps are strings");
    assertNull(parsedSpan.get("parentSpanId"), "root span has no parentSpanId");

    Set<String> attrKeys = attributeKeys(parsedSpan);
    assertTrue(attrKeys.contains("resource.name"));
    assertTrue(attrKeys.contains("operation.name"));
  }

  @Test
  void spanKindIsEncodedAsInteger() throws IOException {
    DDSpan span = startAndFinish("op.server", "GET /api", SPAN_KIND_SERVER);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertEquals(2, ((Number) parsedSpan.get("kind")).intValue(), "SPAN_KIND_SERVER = 2");
  }

  @Test
  void errorSpanHasStatusObject() throws IOException {
    AgentSpan agentSpan = TRACER.startSpan("test", "op.error");
    agentSpan.setResourceName("POST /api/data");
    agentSpan.setError(true);
    agentSpan.setErrorMessage("boom");
    agentSpan.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) agentSpan));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    @SuppressWarnings("unchecked")
    Map<String, Object> status = (Map<String, Object>) parsedSpan.get("status");
    assertEquals("boom", status.get("message"));
    assertEquals(2, ((Number) status.get("code")).intValue(), "STATUS_CODE_ERROR = 2");
  }

  @Test
  void nonErrorSpanHasNoStatusObject() throws IOException {
    DDSpan span = startAndFinish("op.ok", "GET /health", null);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertFalse(parsedSpan.containsKey("status"));
  }

  @Test
  void spanTraceStateOmittedWhenNotPropagated() throws IOException {
    DDSpan span = startAndFinish("op.notracestate", "GET /no-tracestate", null);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertFalse(
        parsedSpan.containsKey("traceState"), "no W3C tracestate propagated should be omitted");
  }

  @Test
  void spanTraceStateIncludedWhenPropagated() throws IOException {
    PropagationTags propagationTags = PropagationTags.factory().empty();
    propagationTags.updateW3CTracestate("vendor=state");
    ExtractedContext parent =
        new ExtractedContext(
            DDTraceId.ONE,
            0L,
            PrioritySampling.UNSET,
            null,
            propagationTags,
            TracePropagationStyle.DATADOG);

    AgentSpan agentSpan = TRACER.startSpan("test", "op.tracestate", parent);
    agentSpan.setResourceName("op.tracestate");
    agentSpan.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) agentSpan));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertEquals("vendor=state", parsedSpan.get("traceState"));
  }

  @Test
  void spanFlagsOmittedWhenNotSampled() throws IOException {
    AgentSpan agentSpan = TRACER.startSpan("test", "op.noflags");
    agentSpan.setResourceName("op.noflags");
    agentSpan.setSamplingPriority(PrioritySampling.USER_DROP, SamplingMechanism.MANUAL);
    // Force export despite the dropped trace-level priority, via span-level sampling -
    // otherwise OtlpTraceCollector#shouldExport would exclude this span entirely.
    agentSpan.setTag(SPAN_SAMPLING_MECHANISM_TAG, SamplingMechanism.SPAN_SAMPLING_RATE);
    agentSpan.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) agentSpan));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertFalse(parsedSpan.containsKey("flags"), "unsampled span should omit flags");
  }

  @Test
  void spanFlagsIncludeSampledBitWhenSampled() throws IOException {
    DDSpan span = startAndFinish("op.sampled", "GET /sampled", null);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertEquals(SAMPLED_TRACE_FLAG, ((Number) parsedSpan.get("flags")).intValue());
  }

  @Test
  void multipleSpansInATraceAreAllWritten() throws IOException {
    AgentSpan parent = TRACER.startSpan("test", "op.parent");
    parent.setResourceName("parent.op");
    AgentSpan child = TRACER.startSpan("test", "op.child", parent.spanContext());
    child.setResourceName("child.op");
    child.finish();
    parent.finish();

    List<CoreSpan<?>> spans = new ArrayList<>();
    spans.add((CoreSpan<?>) parent);
    spans.add((CoreSpan<?>) child);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(spans);
    OtlpPayload payload = collector.collectTraces();

    List<Map<String, Object>> parsedSpans = allSpans(payload);
    assertEquals(2, parsedSpans.size());

    Map<String, Object> parsedChild =
        parsedSpans.stream()
            .filter(s -> "child.op".equals(s.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("child span not found"));
    assertEquals(hexSpanId(((DDSpan) parent).getSpanId()), parsedChild.get("parentSpanId"));
  }

  @Test
  void poisonedSpanResetsCollectorForNextTrace() throws IOException {
    // mid-trace exception (e.g. from a malformed span) must not leave partial state behind
    DDSpan realSpan = startAndFinish("op.first", "GET /first", null);

    CoreSpan<?> poison = mock(CoreSpan.class);
    when(poison.samplingPriority()).thenReturn(1);
    when(poison.getTraceId()).thenThrow(new RuntimeException("boom"));

    List<CoreSpan<?>> poisonedTrace = new ArrayList<>();
    poisonedTrace.add((CoreSpan<?>) realSpan);
    poisonedTrace.add(poison);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    assertThrows(RuntimeException.class, () -> collector.addTrace(poisonedTrace));

    // a normal trace collected afterwards must not see any leftover state from the poisoned one
    DDSpan normalSpan = startAndFinish("op.normal", "GET /normal", null);
    collector.addTrace(asList((CoreSpan<?>) normalSpan));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertEquals("GET /normal", parsedSpan.get("name"));
  }

  @Test
  void spanLinkOmitsTraceStateWhenEmpty() throws IOException {
    AgentSpan linked = TRACER.startSpan("test", "op.linked");
    linked.finish();

    AgentSpan agentSpan = TRACER.startSpan("test", "op.link");
    agentSpan.setResourceName("op.link");
    agentSpan.addLink(SpanLink.from(linked.spanContext()));
    agentSpan.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) agentSpan));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    Map<String, Object> parsedLink = onlyLink(parsedSpan);
    assertFalse(parsedLink.containsKey("traceState"), "empty traceState should be omitted");
  }

  @Test
  void spanLinkIncludesTraceStateWhenPresent() throws IOException {
    AgentSpan linked = TRACER.startSpan("test", "op.linked");
    linked.finish();

    AgentSpan agentSpan = TRACER.startSpan("test", "op.link");
    agentSpan.setResourceName("op.link");
    agentSpan.addLink(
        SpanLink.from(linked.spanContext(), (byte) 0, "vendor=state", SpanAttributes.EMPTY));
    agentSpan.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) agentSpan));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    Map<String, Object> parsedLink = onlyLink(parsedSpan);
    assertEquals("vendor=state", parsedLink.get("traceState"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static DDSpan startAndFinish(String operationName, String resourceName, String spanKind) {
    AgentSpan agentSpan = TRACER.startSpan("test", operationName);
    agentSpan.setResourceName(resourceName);
    if (spanKind != null) {
      agentSpan.setTag(SPAN_KIND, spanKind);
    }
    agentSpan.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DEFAULT);
    agentSpan.finish();
    return (DDSpan) agentSpan;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlySpan(OtlpPayload payload) throws IOException {
    List<Map<String, Object>> spans = allSpans(payload);
    assertEquals(1, spans.size());
    return spans.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> allSpans(OtlpPayload payload) throws IOException {
    byte[] bytes = new byte[payload.getContentLength()];
    payload.getContent().get(bytes);
    String json = new String(bytes, StandardCharsets.UTF_8);
    Map<String, Object> root = JsonMapper.fromJsonToMap(json);

    List<Object> resourceSpans = (List<Object>) root.get("resourceSpans");
    Map<String, Object> resourceSpan = (Map<String, Object>) resourceSpans.get(0);
    List<Object> scopeSpans = (List<Object>) resourceSpan.get("scopeSpans");
    Map<String, Object> scopeSpan = (Map<String, Object>) scopeSpans.get(0);
    List<Object> spans = (List<Object>) scopeSpan.get("spans");

    List<Map<String, Object>> result = new ArrayList<>();
    for (Object span : spans) {
      result.add((Map<String, Object>) span);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlyLink(Map<String, Object> span) {
    List<Object> links = (List<Object>) span.get("links");
    assertEquals(1, links.size());
    return (Map<String, Object>) links.get(0);
  }

  @SuppressWarnings("unchecked")
  private static Set<String> attributeKeys(Map<String, Object> span) {
    List<Object> attributes = (List<Object>) span.get("attributes");
    Set<String> keys = new HashSet<>();
    for (Object attribute : attributes) {
      keys.add((String) ((Map<String, Object>) attribute).get("key"));
    }
    return keys;
  }
}

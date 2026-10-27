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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import datadog.json.JsonMapper;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.KnownTags;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.MockedStatic;
import org.tabletest.junit.TableTest;

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
  void tagsAreEmittedUnderTheirOpenTelemetryNameWhenOtelSemanticsEnabled() throws IOException {
    // The JSON encoder is a second exporter of the same spans, so it must apply the registry's
    // OpenTelemetry naming exactly as the protobuf one does: which transport protocol is configured
    // must not change the attribute names a backend receives.
    Set<String> attrKeys = collectTagsAttributeKeys(true);

    assertTrue(
        attrKeys.contains("http.request.method"),
        "renamed tag must use its OpenTelemetry name; got " + attrKeys);
    assertFalse(
        attrKeys.contains("http.method"),
        "renamed tag must not also appear under its Datadog name; got " + attrKeys);
    assertTrue(
        attrKeys.contains("custom.unregistered"),
        "a tag the registry does not name passes through unchanged; got " + attrKeys);
  }

  @Test
  void tagsAreEmittedUnderTheirDatadogNameWhenOtelSemanticsDisabled() throws IOException {
    // The OpenTelemetry rename is opt-in: with the flag off, existing consumers must keep seeing
    // Datadog names, not the OpenTelemetry ones.
    Set<String> attrKeys = collectTagsAttributeKeys(false);

    assertTrue(
        attrKeys.contains("http.method"),
        "tag must use its Datadog name when OTel semantics are disabled; got " + attrKeys);
    assertFalse(
        attrKeys.contains("http.request.method"),
        "tag must not appear under its OpenTelemetry name when OTel semantics are disabled; got "
            + attrKeys);
    assertTrue(
        attrKeys.contains("custom.unregistered"),
        "a tag the registry does not name passes through unchanged; got " + attrKeys);
  }

  @TableTest({
    "scenario         | kind   | tag               | otelName            ",
    "client hostname  | client | peer.hostname     | server.address      ",
    "server hostname  | server | http.hostname     | server.address      ",
    "server client ip | server | network.client.ip | network.peer.address",
    "client peer port | client | peer.port         | server.port         ",
    "server peer port | server | peer.port         | client.port         "
  })
  void aDirectionScopedTagIsEmittedUnderTheNameForTheSpansKind(
      String kind, String tag, String otelName) throws IOException {
    Config realConfig = Config.get();
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      Config config = mock(Config.class, AdditionalAnswers.delegatesTo(realConfig));
      when(config.isTraceOtelSemanticsEnabled()).thenReturn(true);
      configMock.when(Config::get).thenReturn(config);

      AgentSpan agentSpan = TRACER.startSpan("test", "op.directional");
      agentSpan.setTag(SPAN_KIND, kind);
      agentSpan.setTag(tag, "value");
      agentSpan.finish();

      OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
      collector.addTrace(asList((CoreSpan<?>) agentSpan));
      Set<String> attrKeys = attributeKeys(onlySpan(collector.collectTraces()));

      assertTrue(attrKeys.contains(otelName), "expected " + otelName + "; got " + attrKeys);
      assertFalse(attrKeys.contains(tag), "expected no " + tag + "; got " + attrKeys);
    }
  }

  @TableTest({
    "scenario   | kind     | tag         | expectedPort",
    "server tag | server   | server.port | 8080        ",
    "client tag | client   | peer.port   | 8080        ",
    "internal   | internal | server.port | 8080        "
  })
  void serverPortIsEmittedOnceInEveryDirection(String kind, String tag, long expectedPort)
      throws IOException {
    List<Object> ports = exportedServerPorts(kind, span -> span.setTag(tag, 8080));

    assertEquals(1, ports.size(), "server.port must be emitted once; got " + ports);
    assertEquals(String.valueOf(expectedPort), String.valueOf(ports.get(0)));
  }

  /**
   * Ids are trusted: the server's own port written by id on a client span, beside peer.port (which
   * is server.port there too), is exported twice rather than reconciled.
   */
  @Test
  void aTagWrittenByIdOnTheWrongSideIsExportedAsWritten() throws IOException {
    List<Object> ports =
        exportedServerPorts(
            "client",
            span -> {
              span.setTag(KnownTags.SERVER_PORT_ID, 8080);
              span.setTag(KnownTags.PEER_PORT_ID, 443);
            });

    assertEquals(2, ports.size(), "got " + ports);
  }

  private static List<Object> exportedServerPorts(String kind, Consumer<AgentSpan> tagger)
      throws IOException {
    Config realConfig = Config.get();
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      Config config = mock(Config.class, AdditionalAnswers.delegatesTo(realConfig));
      when(config.isTraceOtelSemanticsEnabled()).thenReturn(true);
      configMock.when(Config::get).thenReturn(config);

      AgentSpan agentSpan = TRACER.startSpan("test", "op.serverPort");
      agentSpan.setTag(SPAN_KIND, kind);
      tagger.accept(agentSpan);
      agentSpan.finish();

      OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
      collector.addTrace(asList((CoreSpan<?>) agentSpan));
      return attributeValues(onlySpan(collector.collectTraces()), "server.port");
    }
  }

  private static Set<String> collectTagsAttributeKeys(boolean otelSemanticsEnabled)
      throws IOException {
    Config realConfig = Config.get();
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      Config config = mock(Config.class, AdditionalAnswers.delegatesTo(realConfig));
      when(config.isTraceOtelSemanticsEnabled()).thenReturn(otelSemanticsEnabled);
      configMock.when(Config::get).thenReturn(config);

      AgentSpan agentSpan = TRACER.startSpan("test", "op.tagged");
      agentSpan.setResourceName("GET /api");
      agentSpan.setTag("http.method", "GET");
      agentSpan.setTag("custom.unregistered", "value");
      agentSpan.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DEFAULT);
      agentSpan.finish();

      OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
      collector.addTrace(asList((CoreSpan<?>) agentSpan));
      return attributeKeys(onlySpan(collector.collectTraces()));
    }
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
  void spanTraceStateIncludesDefaultProbabilityDecision() throws IOException {
    DDSpan span = startAndFinish("op.notracestate", "GET /no-tracestate", null);

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    Map<String, Object> parsedSpan = onlySpan(collector.collectTraces());

    assertTrue(
        parsedSpan.get("traceState").toString().matches("ot=rv:[0-9a-f]{14};th:0"),
        parsedSpan.get("traceState").toString());
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

    assertTrue(
        parsedSpan.get("traceState").toString().matches("ot=rv:[0-9a-f]{14};th:0,vendor=state"),
        parsedSpan.get("traceState").toString());
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
  void traceStateAndFlagsStayPairedAcrossSamplingDecisions() throws IOException {
    Map<String, Object> localFallback = exportSamplingSpan(localProbabilitySpan(1.0, true));
    assertTrue(localFallback.get("traceState").toString().matches("ot=rv:[0-9a-f]{14};th:0"));
    assertEquals(SAMPLED_TRACE_FLAG, ((Number) localFallback.get("flags")).intValue());

    Map<String, Object> inherited = exportSamplingSpan(inheritedSamplingSpan());
    assertEquals("dd=s:1,ot=rv:ef284ace7a91e1;th:8,vendor=state", inherited.get("traceState"));
    assertEquals(SAMPLED_TRACE_FLAG, ((Number) inherited.get("flags")).intValue());

    Map<String, Object> probabilityDrop = exportSamplingSpan(localProbabilitySpan(0.0, false));
    assertTrue(
        probabilityDrop
            .get("traceState")
            .toString()
            .matches("ot=rv:[0-9a-f]{14};th:ffffffffffffff"));
    assertFalse(probabilityDrop.containsKey("flags"));

    DDSpan limiterDrop = localSamplingSpan();
    limiterDrop
        .spanContext()
        .getPropagationTags()
        .tryUpdateProbabilitySamplingDecision(
            PrioritySampling.SAMPLER_DROP,
            SamplingMechanism.AGENT_RATE,
            1.0,
            true,
            limiterDrop.getTraceId().toLong(),
            true);
    Map<String, Object> limiter = exportSamplingSpan(limiterDrop);
    assertNull(limiter.get("traceState"));
    assertFalse(limiter.containsKey("flags"));

    DDSpan nonProbabilityKeep = localProbabilitySpan(0.0, false);
    nonProbabilityKeep.spanContext().getPropagationTags().forceKeep(SamplingMechanism.MANUAL);
    Map<String, Object> nonProbability = exportSamplingSpan(nonProbabilityKeep);
    assertNull(nonProbability.get("traceState"));
    assertEquals(SAMPLED_TRACE_FLAG, ((Number) nonProbability.get("flags")).intValue());
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
  void propagationTagsAreWrittenOncePerTraceOnTheSpanSelectedForSamplingTags() throws IOException {
    AgentSpan root = startRootWithPropagationTags("root.op");
    AgentSpan child = startChild(root, "child.op");
    child.finish();
    root.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) root, (CoreSpan<?>) child));
    List<Map<String, Object>> parsedSpans = allSpans(collector.collectTraces());

    // the collector selects the last exported span of each trace
    assertEquals(asList("root.op", "child.op"), spanNames(parsedSpans));
    assertPropagationTagsOnlyOn(parsedSpans, "child.op", "root.op", "child.op");
  }

  @Test
  void partialFlushWithoutTheLocalRootStillCarriesPropagationTags() throws IOException {
    AgentSpan root = startRootWithPropagationTags("root.op");
    AgentSpan first = startChild(root, "first.child");
    AgentSpan second = startChild(root, "second.child");
    first.finish();
    second.finish();

    // a partial flush exports finished children while the local root is still open
    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) first, (CoreSpan<?>) second));
    List<Map<String, Object>> parsedSpans = allSpans(collector.collectTraces());
    root.finish();

    assertEquals(asList("first.child", "second.child"), spanNames(parsedSpans));
    assertPropagationTagsOnlyOn(parsedSpans, "second.child", "first.child", "second.child");
  }

  @Test
  void propagationTagsAreWrittenOnceForEachTraceInThePayload() throws IOException {
    AgentSpan rootA = startRootWithPropagationTags("a.root");
    AgentSpan childA = startChild(rootA, "a.child");
    AgentSpan rootB = startRootWithPropagationTags("b.root");
    AgentSpan firstB = startChild(rootB, "b.first");
    AgentSpan secondB = startChild(rootB, "b.second");
    AgentSpan rootC = startRootWithPropagationTags("c.root");
    assertNotEquals(rootA.getTraceId(), rootB.getTraceId(), "trace IDs must be distinct");
    assertNotEquals(rootB.getTraceId(), rootC.getTraceId(), "trace IDs must be distinct");
    childA.finish();
    rootA.finish();
    firstB.finish();
    secondB.finish();
    rootC.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) rootA, (CoreSpan<?>) childA));
    // partial flush of trace B, without its local root
    collector.addTrace(asList((CoreSpan<?>) firstB, (CoreSpan<?>) secondB));
    collector.addTrace(asList((CoreSpan<?>) rootC));
    List<Map<String, Object>> parsedSpans = allSpans(collector.collectTraces());
    rootB.finish();

    assertEquals(
        asList("a.root", "a.child", "b.first", "b.second", "c.root"), spanNames(parsedSpans));
    assertPropagationTagsOnlyOn(parsedSpans, "a.child", "a.root", "a.child");
    assertPropagationTagsOnlyOn(parsedSpans, "b.second", "b.first", "b.second");
    assertPropagationTagsOnlyOn(parsedSpans, "c.root", "c.root");
  }

  /** Starts a local root span with a probability sampling decision and a baggage item. */
  private static AgentSpan startRootWithPropagationTags(String resourceName) {
    AgentSpan root = TRACER.startSpan("test", "op.root");
    root.setResourceName(resourceName);
    // 128-bit trace ids are on by default, so the root carries a _dd.p.tid propagation tag
    assertNotEquals(0, root.getTraceId().toHighOrderLong());
    ((DDSpan) root)
        .spanContext()
        .getPropagationTags()
        .tryUpdateProbabilitySamplingDecision(
            PrioritySampling.USER_KEEP,
            SamplingMechanism.REMOTE_ADAPTIVE_RULE,
            0.5,
            false,
            root.getTraceId().toLong(),
            true);
    root.setBaggageItem("baggage.key", "baggage.value");
    return root;
  }

  private static AgentSpan startChild(AgentSpan parent, String resourceName) {
    AgentSpan child = TRACER.startSpan("test", "op.child", parent.spanContext());
    child.setResourceName(resourceName);
    return child;
  }

  private static List<Object> spanNames(List<Map<String, Object>> spans) {
    List<Object> names = new ArrayList<>();
    for (Map<String, Object> span : spans) {
      names.add(span.get("name"));
    }
    return names;
  }

  /**
   * Asserts that, among the exported spans of one trace, only {@code carrier} has the root
   * propagation tags (once each), and that every span keeps its baggage.
   */
  private static void assertPropagationTagsOnlyOn(
      List<Map<String, Object>> spans, String carrier, String... traceSpans) {
    for (String spanName : traceSpans) {
      Map<String, Object> span = spanNamed(spans, spanName);
      if (spanName.equals(carrier)) {
        assertEquals(asList("-12"), attributeValues(span, "_dd.p.dm"), spanName);
        assertEquals(asList("0.5"), attributeValues(span, "_dd.p.ksr"), spanName);
        // the OTLP traceId already carries the upper 64 bits
        assertFalse(attributeKeys(span).contains("_dd.p.tid"), "_dd.p.tid must not be exported");
      } else {
        for (String key : attributeKeys(span)) {
          assertFalse(key.startsWith("_dd.p."), spanName + " must not carry " + key);
        }
      }
      assertEquals(asList("baggage.value"), attributeValues(span, "baggage.key"), spanName);
    }
  }

  @Test
  void baggageDoesNotDuplicateDirectlyWrittenAttributes() throws IOException {
    AgentSpan span = TRACER.startSpan("test", "op.direct");
    span.setResourceName("direct.op");
    span.setBaggageItem("resource.name", "baggage.resource");
    span.setBaggageItem("operation.name", "baggage.operation");
    span.setBaggageItem("thread.name", "baggage.thread");
    span.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    Map<String, Object> parsed = onlySpan(collector.collectTraces());

    // each key must appear once, with the span's own value
    assertEquals(asList("direct.op"), attributeValues(parsed, "resource.name"));
    assertEquals(asList("op.direct"), attributeValues(parsed, "operation.name"));
    assertEquals(asList(Thread.currentThread().getName()), attributeValues(parsed, "thread.name"));
  }

  @TableTest({
    "scenario         | kind   | tag               | otelName            ",
    "client hostname  | client | peer.hostname     | server.address      ",
    "server hostname  | server | http.hostname     | server.address      ",
    "server client ip | server | network.client.ip | network.peer.address"
  })
  void baggageDoesNotDuplicateDirectionScopedAttributes(String kind, String tag, String otelName)
      throws IOException {
    Map<String, Object> parsed = exportWithDirectionalBaggage(true, kind, tag, otelName);

    // the tag is exported under its direction-specific name, once, with the span's own value
    assertEquals(asList("span.value"), attributeValues(parsed, otelName));
    assertTrue(attributeValues(parsed, tag).isEmpty(), "expected no " + tag);
  }

  @Test
  void baggageIsKeptWhenOtelSemanticsDisabled() throws IOException {
    // without the rename the tag keeps its Datadog name, so the baggage key is distinct
    Map<String, Object> parsed =
        exportWithDirectionalBaggage(false, "client", "peer.hostname", "server.address");

    assertEquals(asList("span.value"), attributeValues(parsed, "peer.hostname"));
    assertEquals(asList("baggage.value"), attributeValues(parsed, "server.address"));
  }

  @TableTest({
    "scenario                  | otelSemantics | baggageKey          | httpMethod    |"
        + " httpRequestMethod",
    "otel off, otel-name key   | false         | http.request.method | span.value    |"
        + " baggage.value    ",
    "otel on, otel-name key    | true          | http.request.method |               | span.value  "
        + "     ",
    "otel on, datadog-name key | true          | http.method         | baggage.value | span.value  "
        + "     "
  })
  void baggageIsSkippedOnlyWhenATagIsExportedUnderTheSameKey(
      boolean otelSemantics, String baggageKey, String httpMethod, String httpRequestMethod)
      throws IOException {
    // http.method and http.request.method are aliases of one tag: lookups by either name find it,
    // but only one of them is exported, so baggage under the other name must be kept
    Map<String, Object> parsed =
        exportWithDirectionalBaggage(otelSemantics, "server", "http.method", baggageKey);

    assertEquals(expectedValues(httpMethod), attributeValues(parsed, "http.method"));
    assertEquals(expectedValues(httpRequestMethod), attributeValues(parsed, "http.request.method"));
  }

  private static List<Object> expectedValues(String value) {
    return value == null || value.isEmpty() ? new ArrayList<>() : asList((Object) value);
  }

  private static Map<String, Object> exportWithDirectionalBaggage(
      boolean otelSemanticsEnabled, String kind, String tag, String baggageKey) throws IOException {
    Config realConfig = Config.get();
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      Config config = mock(Config.class, AdditionalAnswers.delegatesTo(realConfig));
      when(config.isTraceOtelSemanticsEnabled()).thenReturn(otelSemanticsEnabled);
      configMock.when(Config::get).thenReturn(config);

      AgentSpan span = TRACER.startSpan("test", "op.directionalBaggage");
      span.setTag(SPAN_KIND, kind);
      span.setTag(tag, "span.value");
      span.setBaggageItem(baggageKey, "baggage.value");
      span.finish();

      OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
      collector.addTrace(asList((CoreSpan<?>) span));
      return onlySpan(collector.collectTraces());
    }
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

  private static DDSpan localSamplingSpan() {
    AgentSpan span = TRACER.startSpan("test", "op.sampling");
    span.setResourceName("op.sampling");
    return (DDSpan) span;
  }

  private static DDSpan localProbabilitySpan(double rate, boolean sampled) {
    DDSpan span = localSamplingSpan();
    span.spanContext()
        .getPropagationTags()
        .tryUpdateProbabilitySamplingDecision(
            sampled ? PrioritySampling.SAMPLER_KEEP : PrioritySampling.SAMPLER_DROP,
            SamplingMechanism.AGENT_RATE,
            rate,
            false,
            span.getTraceId().toLong(),
            true);
    return span;
  }

  private static DDSpan inheritedSamplingSpan() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(
                PropagationTags.HeaderType.W3C, "dd=s:1,vendor=state,ot=rv:ef284ace7a91e1;th:8");
    ExtractedContext parent =
        new ExtractedContext(
            DDTraceId.ONE,
            0L,
            PrioritySampling.SAMPLER_KEEP,
            null,
            propagationTags,
            TracePropagationStyle.TRACECONTEXT);
    AgentSpan span = TRACER.startSpan("test", "op.inherited", parent);
    span.setResourceName("op.inherited");
    return (DDSpan) span;
  }

  private static Map<String, Object> exportSamplingSpan(DDSpan span) throws IOException {
    if (span.getSamplingPriority() <= 0) {
      span.setTag(SPAN_SAMPLING_MECHANISM_TAG, SamplingMechanism.SPAN_SAMPLING_RATE);
    }
    span.finish();
    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    return onlySpan(collector.collectTraces());
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

  private static Map<String, Object> spanNamed(List<Map<String, Object>> spans, String name) {
    return spans.stream()
        .filter(s -> name.equals(s.get("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError(name + " span not found"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlyLink(Map<String, Object> span) {
    List<Object> links = (List<Object>) span.get("links");
    assertEquals(1, links.size());
    return (Map<String, Object>) links.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> attributeValues(Map<String, Object> span, String key) {
    List<Object> values = new ArrayList<>();
    for (Object attribute : (List<Object>) span.get("attributes")) {
      Map<String, Object> entry = (Map<String, Object>) attribute;
      if (key.equals(entry.get("key"))) {
        values.add(((Map<String, Object>) entry.get("value")).values().iterator().next());
      }
    }
    return values;
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

package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link OtlpTraceProto} via {@link OtlpTraceProtoCollector#collectTraces}.
 *
 * <p>Each test case builds real {@link DDSpan} instances via a shared {@link CoreTracer}, collects
 * them using {@link OtlpTraceProtoCollector}, drains the resulting chunked payload into a
 * contiguous byte array, and then parses it back using protobuf's {@link CodedInputStream} to
 * verify the wire encoding against the OpenTelemetry trace proto schema.
 *
 * <p>Relevant proto field numbers (from {@code opentelemetry/proto/trace/v1/trace.proto}):
 *
 * <pre>
 *   TracesData      { ResourceSpans resource_spans = 1; }
 *   ResourceSpans   { Resource resource = 1; ScopeSpans scope_spans = 2; }
 *   ScopeSpans      { InstrumentationScope scope = 1; Span spans = 2; string schema_url = 3; }
 *   InstrumentationScope { string name = 1; string version = 2; }
 *   Span            { bytes trace_id = 1; bytes span_id = 2; string trace_state = 3;
 *                     bytes parent_span_id = 4; string name = 5; SpanKind kind = 6;
 *                     fixed64 start_time_unix_nano = 7; fixed64 end_time_unix_nano = 8;
 *                     KeyValue attributes = 9; Link links = 13; Status status = 15;
 *                     fixed32 flags = 16; }
 *   Status          { string message = 2; StatusCode code = 3; }
 *   Link            { bytes trace_id = 1; bytes span_id = 2; string trace_state = 3;
 *                     KeyValue attributes = 4; fixed32 flags = 6; }
 * </pre>
 */
class OtlpTraceProtoTest {

  static final CoreTracer TRACER = CoreTracer.builder().writer(new LoggingWriter()).build();

  // ── spec classes (test-data descriptors) ──────────────────────────────────

  static final class SpanSpec {
    /** Span resource name → Span.name (proto field 5). */
    final String resourceName;

    /** Passed to {@code startSpan} → attribute "operation.name". */
    final String operationName;

    /** Span type → attribute "span.type". */
    final String spanType;

    /** Span kind tag value; {@code null} → INTERNAL (kind=1). */
    final String spanKind;

    /** Start time in microseconds since epoch → start_time_unix_nano = startMicros * 1000. */
    final long startMicros;

    /** Finish time in microseconds since epoch → end_time_unix_nano = finishMicros * 1000. */
    final long finishMicros;

    /** If true, marks the span as an error → status.code=ERROR(2). */
    final boolean error;

    /** Optional error message → status.message; ignored when {@code error} is false. */
    final String errorMessage;

    /** Sampling priority to set; 0 = not set explicitly. */
    final int samplingPriority;

    /** Override service name; {@code null} → use tracer default. */
    final String serviceName;

    /** Additional tags to set on the span, exercising string/long/boolean/double paths. */
    final Map<String, Object> extraTags;

    /**
     * If ≥ 0, index into the already-built span list to use as parent; creates a child span. If -1,
     * the span is a root span.
     */
    final int parentIndex;

    /**
     * Links to add to this span (one {@link SpanLink} per entry). Each link targets a span that
     * precedes this one in the list. An empty array means no links.
     */
    final LinkSpec[] links;

    /** If true, the span is measured (sets the {@code _dd.measured} attribute). */
    boolean measured;

    /** Non-zero HTTP status code to set via {@code setHttpStatusCode}; 0 = not set. */
    int httpStatusCode;

    /**
     * If true, starts the span under a synthetic {@link ExtractedContext} carrying a known 128-bit
     * trace ID, exercising the high-order bytes of {@code writeTraceId}.
     */
    boolean use128BitTraceId;

    /** Trace origin carried in the extracted parent context; {@code null} = no origin. */
    String origin;

    SpanSpec(
        String resourceName,
        String operationName,
        String spanType,
        String spanKind,
        long startMicros,
        long finishMicros,
        boolean error,
        String errorMessage,
        int samplingPriority,
        String serviceName,
        Map<String, Object> extraTags,
        int parentIndex,
        LinkSpec... links) {
      this.resourceName = resourceName;
      this.operationName = operationName;
      this.spanType = spanType;
      this.spanKind = spanKind;
      this.startMicros = startMicros;
      this.finishMicros = finishMicros;
      this.error = error;
      this.errorMessage = errorMessage;
      this.samplingPriority = samplingPriority;
      this.serviceName = serviceName;
      this.extraTags = extraTags;
      this.parentIndex = parentIndex;
      this.links = links;
    }

    SpanSpec measured() {
      this.measured = true;
      return this;
    }

    SpanSpec httpStatusCode(int code) {
      this.httpStatusCode = code;
      return this;
    }

    SpanSpec use128BitTraceId() {
      this.use128BitTraceId = true;
      return this;
    }

    /**
     * Sets the origin propagated via an {@link ExtractedContext} parent so that {@code
     * metadata.getOrigin()} is non-null and the {@code _dd.origin} attribute is written.
     */
    SpanSpec origin(String origin) {
      this.origin = origin;
      return this;
    }
  }

  /**
   * Descriptor for a single span link: target span index, optional attributes, tracestate, and
   * flags.
   */
  static final class LinkSpec {
    final int targetIndex;
    final SpanAttributes attributes;
    final String traceState;
    final byte traceFlags;

    LinkSpec(int targetIndex) {
      this(targetIndex, SpanAttributes.EMPTY, "", SpanLink.DEFAULT_FLAGS);
    }

    LinkSpec(int targetIndex, SpanAttributes attributes) {
      this(targetIndex, attributes, "", SpanLink.DEFAULT_FLAGS);
    }

    LinkSpec(int targetIndex, SpanAttributes attributes, String traceState, byte traceFlags) {
      this.targetIndex = targetIndex;
      this.attributes = attributes;
      this.traceState = traceState;
      this.traceFlags = traceFlags;
    }
  }

  // ── shorthand builders ────────────────────────────────────────────────────

  private static final long BASE_MICROS = 1_700_000_000_000_000L;
  private static final long DURATION_MICROS = 500_000L; // 500 ms

  /**
   * A known 128-bit trace ID used by {@link SpanSpec#use128BitTraceId} test cases. High-order bits
   * are non-zero so the test can assert the proto encodes them correctly.
   */
  static final DD128bTraceId TRACE_ID_128BIT =
      DD128bTraceId.from(0x0123456789abcdefL, 0xfedcba9876543210L);

  private static SpanSpec span(String resourceName, String operationName, String spanType) {
    return new SpanSpec(
        resourceName,
        operationName,
        spanType,
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        null,
        new HashMap<>(),
        -1);
  }

  private static SpanSpec kindSpan(String resourceName, String kind) {
    return new SpanSpec(
        resourceName,
        "op." + kind,
        "web",
        kind,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        null,
        new HashMap<>(),
        -1);
  }

  private static SpanSpec sampledSpan(String resourceName) {
    return new SpanSpec(
        resourceName,
        "op.sampled",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        PrioritySampling.USER_KEEP,
        null,
        new HashMap<>(),
        -1);
  }

  private static SpanSpec errorSpan(String resourceName, String errorMessage) {
    return new SpanSpec(
        resourceName,
        "op.error",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        true,
        errorMessage,
        0,
        null,
        new HashMap<>(),
        -1);
  }

  private static SpanSpec taggedSpan(String resourceName, Map<String, Object> extraTags) {
    return new SpanSpec(
        resourceName,
        "op.tagged",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        null,
        extraTags,
        -1);
  }

  private static SpanSpec childSpan(String resourceName, int parentIndex) {
    return new SpanSpec(
        resourceName,
        "op.child",
        "web",
        null,
        BASE_MICROS + 10_000,
        BASE_MICROS + DURATION_MICROS - 10_000,
        false,
        null,
        0,
        null,
        new HashMap<>(),
        parentIndex);
  }

  private static SpanSpec serviceSpan(String resourceName, String serviceName) {
    return new SpanSpec(
        resourceName,
        "op.service",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        serviceName,
        new HashMap<>(),
        -1);
  }

  /** A span with {@link SpanLink}s pointing to the spans at the given {@code targetIndices}. */
  private static SpanSpec linkedSpan(String resourceName, int... targetIndices) {
    LinkSpec[] links = new LinkSpec[targetIndices.length];
    for (int i = 0; i < targetIndices.length; i++) {
      links[i] = new LinkSpec(targetIndices[i]);
    }
    return new SpanSpec(
        resourceName,
        "op.linked",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        null,
        new HashMap<>(),
        -1,
        links);
  }

  /**
   * A span with one {@link SpanLink} pointing to the span at {@code targetIndex}, carrying the
   * given {@link SpanAttributes}.
   */
  private static SpanSpec linkedSpanWithAttrs(
      String resourceName, int targetIndex, SpanAttributes attributes) {
    return new SpanSpec(
        resourceName,
        "op.linked",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        null,
        new HashMap<>(),
        -1,
        new LinkSpec(targetIndex, attributes));
  }

  /** A span with one {@link SpanLink} carrying the given W3C tracestate string. */
  private static SpanSpec linkedSpanWithTracestate(
      String resourceName, int targetIndex, String traceState) {
    return new SpanSpec(
        resourceName,
        "op.linked",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        null,
        new HashMap<>(),
        -1,
        new LinkSpec(targetIndex, SpanAttributes.EMPTY, traceState, SpanLink.DEFAULT_FLAGS));
  }

  /** A span with one {@link SpanLink} carrying the given trace flags. */
  private static SpanSpec linkedSpanWithFlags(
      String resourceName, int targetIndex, byte traceFlags) {
    return new SpanSpec(
        resourceName,
        "op.linked",
        "web",
        null,
        BASE_MICROS,
        BASE_MICROS + DURATION_MICROS,
        false,
        null,
        0,
        null,
        new HashMap<>(),
        -1,
        new LinkSpec(targetIndex, SpanAttributes.EMPTY, "", traceFlags));
  }

  private static Map<String, Object> tags(Object... keyValues) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  // ── test cases ─────────────────────────────────────────────────────────────

  static Stream<Arguments> cases() {
    return Stream.of(
        // ── empty ─────────────────────────────────────────────────────────────
        Arguments.of("empty — no spans produces empty payload", emptyList()),

        // ── span kinds ────────────────────────────────────────────────────────
        Arguments.of(
            "minimal span — default INTERNAL kind",
            asList(span("GET /api/users", "servlet.request", "web"))),
        Arguments.of("server span kind", asList(kindSpan("GET /api/users", SPAN_KIND_SERVER))),
        Arguments.of("client span kind", asList(kindSpan("redis.get", SPAN_KIND_CLIENT))),
        Arguments.of("producer span kind", asList(kindSpan("kafka.produce", SPAN_KIND_PRODUCER))),
        Arguments.of("consumer span kind", asList(kindSpan("kafka.consume", SPAN_KIND_CONSUMER))),

        // ── sampling flags ────────────────────────────────────────────────────
        Arguments.of(
            "sampled span — SAMPLED flag set in flags field", asList(sampledSpan("GET /health"))),

        // ── error status ──────────────────────────────────────────────────────
        Arguments.of(
            "error span — status.code=ERROR, no message",
            asList(errorSpan("POST /api/data", null))),
        Arguments.of(
            "error span with message — status.message set",
            asList(errorSpan("POST /api/data", "NullPointerException: value was null"))),

        // ── tag types ─────────────────────────────────────────────────────────
        Arguments.of(
            "span with string tag", asList(taggedSpan("tagged.op", tags("http.method", "GET")))),
        Arguments.of(
            "span with long tag", asList(taggedSpan("tagged.op", tags("http.status_code", 200L)))),
        Arguments.of(
            "span with boolean tag",
            asList(taggedSpan("tagged.op", tags("http.ssl", Boolean.TRUE)))),
        Arguments.of(
            "span with double tag",
            asList(taggedSpan("tagged.op", tags("net.bytes_sent", 1024.5)))),
        Arguments.of(
            "span with multiple mixed tag types",
            asList(
                taggedSpan(
                    "multi.tagged",
                    tags(
                        "http.method",
                        "POST",
                        "http.status_code",
                        201L,
                        "http.ssl",
                        Boolean.FALSE,
                        "latency.ms",
                        3.14)))),

        // ── parent–child relationship ─────────────────────────────────────────
        Arguments.of(
            "child span — parent_span_id must be set",
            asList(span("parent.op", "parent.op", "web"), childSpan("child.op", 0))),

        // ── custom service name ───────────────────────────────────────────────
        Arguments.of(
            "span with different service name — service.name attribute written",
            asList(serviceSpan("GET /users", "my-custom-service"))),

        // ── span links ────────────────────────────────────────────────────────
        Arguments.of(
            "span with one link — link encodes target trace_id and span_id",
            asList(span("anchor.op", "anchor.op", "web"), linkedSpan("linked.op", 0))),
        Arguments.of(
            "span with multiple links to different spans",
            asList(
                span("target.a", "op.a", "web"),
                span("target.b", "op.b", "web"),
                linkedSpan("multi.linked", 0, 1))),
        Arguments.of(
            "span link with attributes — link attributes written to proto",
            asList(
                span("anchor.op", "anchor.op", "web"),
                linkedSpanWithAttrs(
                    "attr.linked",
                    0,
                    SpanAttributes.builder().put("link.source", "test").build()))),
        Arguments.of(
            "span link with tracestate — Link.trace_state field written",
            asList(
                span("anchor.op", "anchor.op", "web"),
                linkedSpanWithTracestate("tracestate.linked", 0, "vendor=abc;p=123"))),
        Arguments.of(
            "span link with non-default flags — extra flag bit preserved alongside SAMPLED",
            asList(
                span("anchor.op", "anchor.op", "web"),
                linkedSpanWithFlags("flags.linked", 0, (byte) 0x02))),

        // ── metadata paths ────────────────────────────────────────────────────
        Arguments.of(
            "measured span — _dd.measured attribute written",
            asList(span("measured.op", "op.measured", "web").measured())),
        Arguments.of(
            "span with http status code — http.status_code written via setHttpStatusCode",
            asList(span("GET /resource", "servlet.request", "web").httpStatusCode(404))),
        Arguments.of(
            "span with origin — _dd.origin attribute written",
            asList(span("GET /api", "servlet.request", "web").origin("rum"))),
        Arguments.of(
            "span with 128-bit trace ID — high-order trace_id bytes non-zero",
            asList(span("GET /api", "servlet.request", "web").use128BitTraceId())),

        // ── multiple spans in one payload ─────────────────────────────────────
        Arguments.of(
            "multiple spans — three spans under the same default scope",
            asList(
                span("first.span", "op.first", "db"),
                span("second.span", "op.second", "web"),
                kindSpan("third.span", SPAN_KIND_SERVER))));
  }

  // ── parameterized test ────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void testCollectTraces(String caseName, List<SpanSpec> specs) throws IOException {
    List<DDSpan> spans = buildSpans(specs);

    OtlpTraceProtoCollector.INSTANCE.addTrace(spans);
    OtlpPayload payload = OtlpTraceProtoCollector.INSTANCE.collectTraces();

    if (spans.isEmpty()) {
      assertEquals(0, payload.getContentLength(), "empty span list must produce empty payload");
      return;
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream(payload.getContentLength());
    payload.drain(baos::write);
    byte[] bytes = baos.toByteArray();
    assertTrue(bytes.length > 0, "non-empty span list must produce bytes");

    // ── parse TracesData ─────────────────────────────────────────────────
    // Full payload encodes a single TracesData.resource_spans entry (field 1, LEN).
    CodedInputStream td = CodedInputStream.newInstance(bytes);
    int tdTag = td.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tdTag), "TracesData.resource_spans is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tdTag));
    CodedInputStream rs = td.readBytes().newCodedInput();
    assertTrue(td.isAtEnd(), "expected exactly one ResourceSpans");

    // ── parse ResourceSpans ──────────────────────────────────────────────
    // Fields: resource=1, scope_spans=2
    boolean resourceFound = false;
    CodedInputStream ss = null;
    while (!rs.isAtEnd()) {
      int rsTag = rs.readTag();
      switch (WireFormat.getTagFieldNumber(rsTag)) {
        case 1:
          verifyResource(rs.readBytes().newCodedInput());
          resourceFound = true;
          break;
        case 2:
          ss = rs.readBytes().newCodedInput();
          break;
        default:
          rs.skipField(rsTag);
      }
    }
    assertTrue(resourceFound, "Resource must be present in ResourceSpans");
    assertNotNull(ss, "ScopeSpans must be present in ResourceSpans");

    // ── parse ScopeSpans ─────────────────────────────────────────────────
    // Fields: scope=1, spans=2 (repeated), schema_url=3
    List<byte[]> spanBlobs = new ArrayList<>();
    while (!ss.isAtEnd()) {
      int ssTag = ss.readTag();
      switch (WireFormat.getTagFieldNumber(ssTag)) {
        case 1:
          verifyDefaultScope(ss.readBytes().newCodedInput());
          break;
        case 2:
          spanBlobs.add(ss.readBytes().toByteArray());
          break;
        default:
          ss.skipField(ssTag);
      }
    }
    assertEquals(spans.size(), spanBlobs.size(), "span count mismatch in case: " + caseName);

    // ── verify each span ─────────────────────────────────────────────────
    for (int i = 0; i < spans.size(); i++) {
      verifySpan(
          CodedInputStream.newInstance(spanBlobs.get(i)), spans.get(i), specs.get(i), caseName);
    }
  }

  // ── span construction ─────────────────────────────────────────────────────

  /** Builds {@link DDSpan} instances from the given specs, collecting them in order. */
  private static List<DDSpan> buildSpans(List<SpanSpec> specs) {
    List<DDSpan> spans = new ArrayList<>(specs.size());
    for (SpanSpec spec : specs) {
      AgentSpan agentSpan;
      if (spec.use128BitTraceId) {
        ExtractedContext parent128 =
            new ExtractedContext(
                TRACE_ID_128BIT,
                0L,
                PrioritySampling.UNSET,
                null,
                PropagationTags.factory().empty(),
                TracePropagationStyle.DATADOG);
        agentSpan = TRACER.startSpan("test", spec.operationName, parent128, spec.startMicros);
      } else if (spec.origin != null) {
        ExtractedContext parentWithOrigin =
            new ExtractedContext(
                DDTraceId.ONE,
                0L,
                PrioritySampling.UNSET,
                spec.origin,
                PropagationTags.factory().empty(),
                TracePropagationStyle.DATADOG);
        agentSpan =
            TRACER.startSpan("test", spec.operationName, parentWithOrigin, spec.startMicros);
      } else if (spec.parentIndex >= 0) {
        agentSpan =
            TRACER.startSpan(
                "test",
                spec.operationName,
                spans.get(spec.parentIndex).context(),
                spec.startMicros);
      } else {
        agentSpan = TRACER.startSpan("test", spec.operationName, spec.startMicros);
      }

      agentSpan.setResourceName(spec.resourceName);
      agentSpan.setSpanType(spec.spanType);

      if (spec.spanKind != null) {
        agentSpan.setTag(SPAN_KIND, spec.spanKind);
      }
      if (spec.serviceName != null) {
        agentSpan.setServiceName(spec.serviceName);
      }
      if (spec.samplingPriority != 0) {
        agentSpan.setSamplingPriority(spec.samplingPriority, SamplingMechanism.DEFAULT);
      }
      if (spec.error) {
        agentSpan.setError(true);
        if (spec.errorMessage != null) {
          agentSpan.setErrorMessage(spec.errorMessage);
        }
      }
      if (spec.measured) {
        agentSpan.setMeasured(true);
      }
      if (spec.httpStatusCode != 0) {
        agentSpan.setHttpStatusCode(spec.httpStatusCode);
      }

      spec.extraTags.forEach(
          (key, value) -> {
            if (value instanceof String) agentSpan.setTag(key, (String) value);
            else if (value instanceof Long) agentSpan.setTag(key, (long) (Long) value);
            else if (value instanceof Boolean) agentSpan.setTag(key, (boolean) (Boolean) value);
            else if (value instanceof Double) agentSpan.setTag(key, (double) (Double) value);
          });

      for (LinkSpec link : spec.links) {
        agentSpan.addLink(
            SpanLink.from(
                spans.get(link.targetIndex).context(),
                link.traceFlags,
                link.traceState,
                link.attributes));
      }

      agentSpan.finish(spec.finishMicros);
      spans.add((DDSpan) agentSpan);
    }
    return spans;
  }

  // ── verification helpers ──────────────────────────────────────────────────

  /**
   * Parses a {@code Resource} message body and asserts it contains a {@code service.name}
   * attribute.
   *
   * <pre>
   *   Resource { repeated KeyValue attributes = 1; }
   * </pre>
   */
  private static void verifyResource(CodedInputStream res) throws IOException {
    boolean foundServiceName = false;
    while (!res.isAtEnd()) {
      int tag = res.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) {
        String key = readKeyValueKey(res.readBytes().newCodedInput());
        if ("service.name".equals(key)) {
          foundServiceName = true;
        }
      } else {
        res.skipField(tag);
      }
    }
    assertTrue(foundServiceName, "Resource must contain a 'service.name' attribute");
  }

  /**
   * Parses an {@code InstrumentationScope} message body and asserts the scope name is the empty
   * string used by the default trace scope.
   *
   * <pre>
   *   InstrumentationScope { string name = 1; string version = 2; }
   * </pre>
   */
  private static void verifyDefaultScope(CodedInputStream scope) throws IOException {
    String parsedName = null;
    while (!scope.isAtEnd()) {
      int tag = scope.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) {
        parsedName = scope.readString();
      } else {
        scope.skipField(tag);
      }
    }
    assertEquals("", parsedName, "default trace scope must have an empty name");
  }

  /**
   * Parses a {@code Span} message body and asserts all fields match the given DDSpan and SpanSpec.
   *
   * <pre>
   *   Span { trace_id=1, span_id=2, trace_state=3, parent_span_id=4, name=5, kind=6,
   *          start_time_unix_nano=7, end_time_unix_nano=8, attributes=9 (repeated),
   *          links=13 (repeated), status=15, flags=16 }
   * </pre>
   */
  private static void verifySpan(CodedInputStream sp, DDSpan span, SpanSpec spec, String caseName)
      throws IOException {
    byte[] parsedTraceId = null;
    byte[] parsedSpanId = null;
    byte[] parsedParentSpanId = null;
    String parsedName = null;
    int parsedKind = -1;
    long parsedStartNano = -1;
    long parsedEndNano = -1;
    int parsedFlags = 0;
    boolean statusFound = false;
    boolean statusIsError = false;
    String parsedStatusMessage = null;
    Set<String> attrKeys = new HashSet<>();
    int linkCount = 0;

    while (!sp.isAtEnd()) {
      int tag = sp.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          parsedTraceId = sp.readBytes().toByteArray();
          break;
        case 2:
          parsedSpanId = sp.readBytes().toByteArray();
          break;
        case 3:
          sp.skipField(
              tag); // trace_state: absent for locally-started spans, present when propagated
          break;
        case 4:
          parsedParentSpanId = sp.readBytes().toByteArray();
          break;
        case 5:
          parsedName = sp.readString();
          break;
        case 6:
          parsedKind = sp.readEnum();
          break;
        case 7:
          parsedStartNano = sp.readFixed64();
          break;
        case 8:
          parsedEndNano = sp.readFixed64();
          break;
        case 9:
          attrKeys.add(readKeyValueKey(sp.readBytes().newCodedInput()));
          break;
        case 13:
          verifyLink(sp.readBytes().newCodedInput(), spec.links[linkCount], caseName);
          linkCount++;
          break;
        case 15:
          {
            CodedInputStream status = sp.readBytes().newCodedInput();
            statusFound = true;
            while (!status.isAtEnd()) {
              int st = status.readTag();
              switch (WireFormat.getTagFieldNumber(st)) {
                case 2:
                  parsedStatusMessage = status.readString();
                  break;
                case 3:
                  statusIsError = status.readEnum() == 2; // STATUS_CODE_ERROR = 2
                  break;
                default:
                  status.skipField(st);
              }
            }
            break;
          }
        case 16:
          parsedFlags = sp.readFixed32();
          break;
        default:
          sp.skipField(tag);
      }
    }

    // ── trace_id (field 1): 16 bytes ─────────────────────────────────────────
    assertNotNull(parsedTraceId, "trace_id must be present [" + caseName + "]");
    assertEquals(16, parsedTraceId.length, "trace_id must be 16 bytes [" + caseName + "]");
    if (spec.use128BitTraceId) {
      // high-order bytes occupy parsedTraceId[8..15] (little-endian in the wire format)
      long highOrderBytes = readLittleEndianLong(copyOfRange(parsedTraceId, 8, 16));
      assertNotEquals(
          0L,
          highOrderBytes,
          "128-bit trace_id high-order bytes must be non-zero [" + caseName + "]");
    }

    // ── span_id (field 2): 8 bytes, encodes span.getSpanId() ─────────────────
    assertNotNull(parsedSpanId, "span_id must be present [" + caseName + "]");
    assertEquals(8, parsedSpanId.length, "span_id must be 8 bytes [" + caseName + "]");
    assertEquals(
        span.getSpanId(),
        readLittleEndianLong(parsedSpanId),
        "span_id mismatch [" + caseName + "]");

    // ── parent_span_id (field 4) ──────────────────────────────────────────────
    if (spec.parentIndex >= 0) {
      assertNotNull(
          parsedParentSpanId, "parent_span_id must be present for child span [" + caseName + "]");
      assertEquals(
          8, parsedParentSpanId.length, "parent_span_id must be 8 bytes [" + caseName + "]");
      assertEquals(
          span.getParentId(),
          readLittleEndianLong(parsedParentSpanId),
          "parent_span_id mismatch [" + caseName + "]");
    } else {
      // root spans either omit the field or write zero bytes
      if (parsedParentSpanId != null) {
        assertEquals(
            0L,
            readLittleEndianLong(parsedParentSpanId),
            "root span parent_span_id must be zero [" + caseName + "]");
      }
    }

    // ── name (field 5): resource name ─────────────────────────────────────────
    assertEquals(
        spec.resourceName, parsedName, "Span.name (resource name) mismatch [" + caseName + "]");

    // ── kind (field 6): SpanKind enum ─────────────────────────────────────────
    assertEquals(expectedKind(spec.spanKind), parsedKind, "kind mismatch [" + caseName + "]");

    // ── start_time_unix_nano (field 7) ────────────────────────────────────────
    assertEquals(
        spec.startMicros * 1000L,
        parsedStartNano,
        "start_time_unix_nano mismatch [" + caseName + "]");

    // ── end_time_unix_nano (field 8) ──────────────────────────────────────────
    assertEquals(
        spec.finishMicros * 1000L, parsedEndNano, "end_time_unix_nano mismatch [" + caseName + "]");

    // ── flags (field 16): SAMPLED flag reflects span.samplingPriority() > 0 ──
    // The default tracer sampler keeps all spans (priority > 0), so the SAMPLED flag is set for
    // every span. We verify it is set when we've explicitly requested it; we don't assert it is
    // absent otherwise because the default sampler may still set a positive priority.
    if (spec.samplingPriority > 0) {
      assertTrue(
          (parsedFlags & OtlpTraceProto.SAMPLED_TRACE_FLAG) != 0,
          "SAMPLED flag must be set in flags [" + caseName + "]");
    }

    // ── attributes (field 9): mandatory Datadog attributes ───────────────────
    assertTrue(
        attrKeys.contains("resource.name"),
        "attributes must include 'resource.name' [" + caseName + "]");
    assertTrue(
        attrKeys.contains("operation.name"),
        "attributes must include 'operation.name' [" + caseName + "]");
    assertTrue(
        attrKeys.contains("span.type"), "attributes must include 'span.type' [" + caseName + "]");

    // service.name attribute is written only when the span's service differs from the default
    if (spec.serviceName != null) {
      assertTrue(
          attrKeys.contains("service.name"),
          "attributes must include 'service.name' when service is overridden [" + caseName + "]");
    }

    // extra user tags must appear as attributes
    for (String key : spec.extraTags.keySet()) {
      assertTrue(
          attrKeys.contains(key),
          "attributes must include extra tag '" + key + "' [" + caseName + "]");
    }

    if (spec.measured) {
      assertTrue(
          attrKeys.contains("_dd.measured"),
          "attributes must include '_dd.measured' for measured spans [" + caseName + "]");
    }
    if (spec.httpStatusCode != 0) {
      assertTrue(
          attrKeys.contains("http.status_code"),
          "attributes must include 'http.status_code' when set via setHttpStatusCode ["
              + caseName
              + "]");
    }
    if (spec.origin != null) {
      assertTrue(
          attrKeys.contains("_dd.origin"),
          "attributes must include '_dd.origin' when origin is set [" + caseName + "]");
    }

    // ── status (field 15) ─────────────────────────────────────────────────────
    if (spec.error) {
      assertTrue(statusFound, "status must be present for error span [" + caseName + "]");
      assertTrue(statusIsError, "status.code must be ERROR(2) [" + caseName + "]");
      if (spec.errorMessage != null) {
        assertEquals(
            spec.errorMessage, parsedStatusMessage, "status.message mismatch [" + caseName + "]");
      } else {
        assertNull(
            parsedStatusMessage, "status.message must be absent when not set [" + caseName + "]");
      }
    } else {
      assertFalse(statusFound, "status must be absent for non-error span [" + caseName + "]");
    }

    // ── links (field 13) ──────────────────────────────────────────────────────
    assertEquals(spec.links.length, linkCount, "link count mismatch [" + caseName + "]");
  }

  /**
   * Parses a {@code Span.Link} message body and verifies trace_id, span_id, and (if expected) link
   * attributes are present.
   *
   * <pre>
   *   Link { bytes trace_id = 1; bytes span_id = 2; string trace_state = 3;
   *          KeyValue attributes = 4; fixed32 flags = 6; }
   * </pre>
   */
  private static void verifyLink(CodedInputStream link, LinkSpec linkSpec, String caseName)
      throws IOException {
    byte[] traceId = null;
    byte[] spanId = null;
    String parsedTraceState = null;
    Set<String> linkAttrKeys = new HashSet<>();
    int parsedFlags = 0;
    while (!link.isAtEnd()) {
      int tag = link.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          traceId = link.readBytes().toByteArray();
          break;
        case 2:
          spanId = link.readBytes().toByteArray();
          break;
        case 3:
          parsedTraceState = link.readString();
          break;
        case 4:
          linkAttrKeys.add(readKeyValueKey(link.readBytes().newCodedInput()));
          break;
        case 6:
          parsedFlags = link.readFixed32();
          break;
        default:
          link.skipField(tag);
      }
    }
    assertNotNull(traceId, "Link.trace_id must be present [" + caseName + "]");
    assertEquals(16, traceId.length, "Link.trace_id must be 16 bytes [" + caseName + "]");
    assertNotNull(spanId, "Link.span_id must be present [" + caseName + "]");
    assertEquals(8, spanId.length, "Link.span_id must be 8 bytes [" + caseName + "]");
    if (!linkSpec.traceState.isEmpty()) {
      assertEquals(
          linkSpec.traceState, parsedTraceState, "Link.trace_state mismatch [" + caseName + "]");
    }
    // SpanLink.from() ORs in the SAMPLED_FLAG (0x01) when the target context has positive
    // sampling priority, which all test anchor spans have via the default tracer sampler.
    int expectedFlags = Byte.toUnsignedInt((byte) (linkSpec.traceFlags | 0x01));
    assertEquals(expectedFlags, parsedFlags, "Link.flags mismatch [" + caseName + "]");
    for (String expectedKey : linkSpec.attributes.asMap().keySet()) {
      assertTrue(
          linkAttrKeys.contains(expectedKey),
          "Link attributes must include '" + expectedKey + "' [" + caseName + "]");
    }
  }

  // ── proto parsing helpers ─────────────────────────────────────────────────

  /**
   * Returns the expected SpanKind enum value for the given Datadog span kind tag value.
   *
   * <pre>
   *   SPAN_KIND_UNSPECIFIED = 0  (unused)
   *   SPAN_KIND_INTERNAL    = 1  (default)
   *   SPAN_KIND_SERVER      = 2
   *   SPAN_KIND_CLIENT      = 3
   *   SPAN_KIND_PRODUCER    = 4
   *   SPAN_KIND_CONSUMER    = 5
   * </pre>
   */
  private static int expectedKind(String spanKind) {
    if (SPAN_KIND_SERVER.equals(spanKind)) return 2;
    if (SPAN_KIND_CLIENT.equals(spanKind)) return 3;
    if (SPAN_KIND_PRODUCER.equals(spanKind)) return 4;
    if (SPAN_KIND_CONSUMER.equals(spanKind)) return 5;
    return 1; // INTERNAL
  }

  /**
   * Reads a {@code KeyValue} body and returns the key (field 1). The value is skipped; its encoding
   * is covered by {@code OtlpCommonProtoTest}.
   */
  private static String readKeyValueKey(CodedInputStream kv) throws IOException {
    String key = null;
    while (!kv.isAtEnd()) {
      int tag = kv.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) {
        key = kv.readString();
      } else {
        kv.skipField(tag);
      }
    }
    return key;
  }

  /** Reads a little-endian 64-bit integer from the first 8 bytes of the given array. */
  private static long readLittleEndianLong(byte[] bytes) {
    long value = 0;
    for (int i = 7; i >= 0; i--) {
      value = (value << 8) | (bytes[i] & 0xFF);
    }
    return value;
  }
}

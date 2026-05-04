package datadog.trace.core.otlp.logs;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.SAMPLED_TRACE_FLAG;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;
import datadog.trace.bootstrap.otlp.logs.OtlpScopedLogsVisitor;
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
import javax.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link OtlpLogsProto} via {@link OtlpLogsProtoCollector#collectLogs}.
 *
 * <p>Each test case constructs {@link OtlpLogRecord} instances (using real {@link DDSpan} contexts
 * where needed), collects them via {@link OtlpLogsProtoCollector}, drains the resulting chunked
 * payload into a contiguous byte array, and then parses it back using protobuf's {@link
 * CodedInputStream} to verify the wire encoding against the OpenTelemetry logs proto schema.
 *
 * <p>Relevant proto field numbers (from {@code opentelemetry/proto/logs/v1/logs.proto}):
 *
 * <pre>
 *   LogsData     { repeated ResourceLogs resource_logs = 1; }
 *   ResourceLogs { Resource resource = 1; repeated ScopeLogs scope_logs = 2; }
 *   ScopeLogs    { InstrumentationScope scope = 1; repeated LogRecord log_records = 2;
 *                  string schema_url = 3; }
 *   InstrumentationScope { string name = 1; string version = 2; }
 *   LogRecord    { fixed64 time_unix_nano = 1; SeverityNumber severity_number = 2;
 *                  string severity_text = 3; AnyValue body = 5;
 *                  repeated KeyValue attributes = 6; fixed32 flags = 8;
 *                  bytes trace_id = 9; bytes span_id = 10;
 *                  fixed64 observed_time_unix_nano = 11; string event_name = 12; }
 * </pre>
 */
class OtlpLogsProtoTest {

  static final CoreTracer TRACER = CoreTracer.builder().writer(new LoggingWriter()).build();

  // ── well-known scopes ──────────────────────────────────────────────────────

  static final OtelInstrumentationScope DEFAULT_SCOPE =
      new OtelInstrumentationScope("test.logger", null, null);
  static final OtelInstrumentationScope VERSIONED_SCOPE =
      new OtelInstrumentationScope("io.example", "2.0.0", null);
  static final OtelInstrumentationScope SCHEMA_SCOPE =
      new OtelInstrumentationScope("io.example", "1.0", "https://opentelemetry.io/schemas/1.0");

  // ── spec class ─────────────────────────────────────────────────────────────

  static final class LogSpec {
    /** Instrumentation scope for this log record → drives ScopeLogs grouping. */
    final OtelInstrumentationScope scope;

    /** time_unix_nano (proto field 1). */
    final long timestampNanos;

    /** observed_time_unix_nano (proto field 11). */
    final long observedNanos;

    /** severity_number (proto field 2). */
    final int severityNumber;

    /** severity_text (proto field 3); {@code null} → field absent. */
    @Nullable final String severityText;

    /** body.string_value (proto field 5 → AnyValue field 1); {@code null} → field absent. */
    @Nullable final String body;

    /** Extra attributes written via {@code visitAttribute} before the log record. */
    final Map<String, Object> attrs;

    /**
     * If ≥ 0, index into the pre-built span list for the span context, encoding trace_id, span_id,
     * and flags. If -1, no span context → those fields absent.
     */
    final int spanContextIndex;

    /** event_name (proto field 12); {@code null} → field absent. */
    @Nullable final String eventName;

    /**
     * If true, the span at {@code spanContextIndex} is started under a known 128-bit trace ID so
     * the high-order bytes of trace_id are non-zero.
     */
    boolean use128BitTraceId;

    LogSpec(
        OtelInstrumentationScope scope,
        long timestampNanos,
        long observedNanos,
        int severityNumber,
        @Nullable String severityText,
        @Nullable String body,
        Map<String, Object> attrs,
        int spanContextIndex,
        @Nullable String eventName) {
      this.scope = scope;
      this.timestampNanos = timestampNanos;
      this.observedNanos = observedNanos;
      this.severityNumber = severityNumber;
      this.severityText = severityText;
      this.body = body;
      this.attrs = attrs;
      this.spanContextIndex = spanContextIndex;
      this.eventName = eventName;
    }

    LogSpec use128BitTraceId() {
      this.use128BitTraceId = true;
      return this;
    }
  }

  // ── shorthand builders ─────────────────────────────────────────────────────

  private static final long BASE_NANOS = 1_700_000_000_000_000_000L;
  private static final long OBSERVED_OFFSET_NANOS = 1_000_000L; // 1 ms

  /** A known 128-bit trace ID; high-order bits are non-zero to exercise the encoding path. */
  static final DD128bTraceId TRACE_ID_128BIT =
      DD128bTraceId.from(0x0123456789abcdefL, 0xfedcba9876543210L);

  private static LogSpec infoLog(String body) {
    return new LogSpec(
        DEFAULT_SCOPE,
        BASE_NANOS,
        BASE_NANOS + OBSERVED_OFFSET_NANOS,
        9,
        "INFO",
        body,
        new HashMap<>(),
        -1,
        null);
  }

  private static LogSpec scopedLog(OtelInstrumentationScope scope, String body) {
    return new LogSpec(
        scope,
        BASE_NANOS,
        BASE_NANOS + OBSERVED_OFFSET_NANOS,
        9,
        "INFO",
        body,
        new HashMap<>(),
        -1,
        null);
  }

  private static LogSpec severityLog(
      int severityNumber, @Nullable String severityText, String body) {
    return new LogSpec(
        DEFAULT_SCOPE,
        BASE_NANOS,
        BASE_NANOS + OBSERVED_OFFSET_NANOS,
        severityNumber,
        severityText,
        body,
        new HashMap<>(),
        -1,
        null);
  }

  private static LogSpec contextLog(String body, int spanContextIndex) {
    return new LogSpec(
        DEFAULT_SCOPE,
        BASE_NANOS,
        BASE_NANOS + OBSERVED_OFFSET_NANOS,
        9,
        "INFO",
        body,
        new HashMap<>(),
        spanContextIndex,
        null);
  }

  private static LogSpec eventLog(String body, String eventName) {
    return new LogSpec(
        DEFAULT_SCOPE,
        BASE_NANOS,
        BASE_NANOS + OBSERVED_OFFSET_NANOS,
        9,
        "INFO",
        body,
        new HashMap<>(),
        -1,
        eventName);
  }

  private static LogSpec eventOnlyLog(String eventName) {
    return new LogSpec(
        DEFAULT_SCOPE,
        BASE_NANOS,
        BASE_NANOS + OBSERVED_OFFSET_NANOS,
        9,
        "INFO",
        null,
        new HashMap<>(),
        -1,
        eventName);
  }

  private static LogSpec taggedLog(String body, Map<String, Object> attrs) {
    return new LogSpec(
        DEFAULT_SCOPE,
        BASE_NANOS,
        BASE_NANOS + OBSERVED_OFFSET_NANOS,
        9,
        "INFO",
        body,
        attrs,
        -1,
        null);
  }

  private static Map<String, Object> attrs(Object... keyValues) {
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
        Arguments.of("empty — no log records produces empty payload", emptyList()),

        // ── minimal ───────────────────────────────────────────────────────────
        Arguments.of("minimal log record — INFO with body", asList(infoLog("Hello, World!"))),

        // ── severity ──────────────────────────────────────────────────────────
        Arguments.of(
            "TRACE severity — severity_number=1, severity_text=TRACE",
            asList(severityLog(1, "TRACE", "trace message"))),
        Arguments.of(
            "DEBUG severity — severity_number=5", asList(severityLog(5, "DEBUG", "debug message"))),
        Arguments.of(
            "WARN severity — severity_number=13", asList(severityLog(13, "WARN", "warn message"))),
        Arguments.of(
            "ERROR severity — severity_number=17",
            asList(severityLog(17, "ERROR", "error message"))),
        Arguments.of(
            "FATAL severity — severity_number=21",
            asList(severityLog(21, "FATAL", "fatal message"))),
        Arguments.of(
            "null severity text — severity_text field absent",
            asList(severityLog(9, null, "no severity text"))),

        // ── body ──────────────────────────────────────────────────────────────
        Arguments.of("empty body string — body.string_value is empty", asList(infoLog(""))),
        Arguments.of(
            "Unicode body — UTF-8 multi-byte chars encoded correctly",
            asList(infoLog("日本語テスト emoji 🎉"))),

        // ── span context ──────────────────────────────────────────────────────
        Arguments.of(
            "log with span context — trace_id and span_id encoded, SAMPLED flag set",
            asList(infoLog("no context"), contextLog("with context", 0))),
        Arguments.of(
            "log with 128-bit trace ID — high-order trace_id bytes non-zero",
            asList(contextLog("128-bit trace", 0).use128BitTraceId())),

        // ── event name ────────────────────────────────────────────────────────
        Arguments.of(
            "log with event name — event_name field written",
            asList(eventLog("button clicked", "user.interaction"))),
        Arguments.of("null event name — event_name field absent", asList(infoLog("no event name"))),
        Arguments.of(
            "event-only log — body absent when null, event_name field present",
            asList(eventOnlyLog("user.interaction"))),

        // ── attributes ────────────────────────────────────────────────────────
        Arguments.of(
            "log with string attribute",
            asList(taggedLog("tagged", attrs("service.name", "my-service")))),
        Arguments.of(
            "log with long attribute",
            asList(taggedLog("tagged", attrs("http.status_code", 200L)))),
        Arguments.of(
            "log with boolean attribute",
            asList(taggedLog("tagged", attrs("error", Boolean.TRUE)))),
        Arguments.of(
            "log with double attribute", asList(taggedLog("tagged", attrs("latency.ms", 3.14)))),
        Arguments.of(
            "log with multiple mixed attributes",
            asList(
                taggedLog(
                    "multi-tagged",
                    attrs(
                        "service.name",
                        "svc",
                        "http.status_code",
                        500L,
                        "error",
                        Boolean.TRUE,
                        "latency.ms",
                        1.5)))),

        // ── instrumentation scope ─────────────────────────────────────────────
        Arguments.of(
            "versioned scope — scope name and version written",
            asList(scopedLog(VERSIONED_SCOPE, "versioned log"))),
        Arguments.of(
            "scope with schema URL — schema_url field written in ScopeLogs",
            asList(scopedLog(SCHEMA_SCOPE, "schema log"))),

        // ── multiple log records ───────────────────────────────────────────────
        Arguments.of(
            "multiple log records under same scope",
            asList(infoLog("first"), infoLog("second"), severityLog(17, "ERROR", "third"))),

        // ── multiple scopes ────────────────────────────────────────────────────
        Arguments.of(
            "multiple scopes — each scope produces its own ScopeLogs entry",
            asList(
                scopedLog(DEFAULT_SCOPE, "scope A record"),
                scopedLog(VERSIONED_SCOPE, "scope B record"))));
  }

  // ── parameterized test ────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void testCollectLogs(String caseName, List<LogSpec> specs) throws IOException {
    List<DDSpan> spans = buildSpans(specs);

    OtlpPayload payload =
        OtlpLogsProtoCollector.INSTANCE.collectLogs(
            visitor -> {
              OtelInstrumentationScope lastScope = null;
              OtlpScopedLogsVisitor scoped = null;
              for (LogSpec spec : specs) {
                if (!spec.scope.equals(lastScope)) {
                  scoped = visitor.visitScopedLogs(spec.scope);
                  lastScope = spec.scope;
                }
                for (Map.Entry<String, Object> e : spec.attrs.entrySet()) {
                  scoped.visitAttribute(attrType(e.getValue()), e.getKey(), e.getValue());
                }
                AgentSpanContext ctx = resolveContext(spans, spec);
                scoped.visitLogRecord(
                    new OtlpLogRecord(
                        spec.scope,
                        spec.timestampNanos,
                        spec.observedNanos,
                        spec.severityNumber,
                        spec.severityText,
                        spec.body,
                        emptyMap(),
                        ctx,
                        spec.eventName));
              }
            });

    if (specs.isEmpty()) {
      assertEquals(0, payload.getContentLength(), "empty specs must produce empty payload");
      return;
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream(payload.getContentLength());
    payload.drain(baos::write);
    byte[] bytes = baos.toByteArray();
    assertTrue(bytes.length > 0, "non-empty specs must produce bytes");

    // ── parse LogsData ────────────────────────────────────────────────────
    CodedInputStream ld = CodedInputStream.newInstance(bytes);
    int ldTag = ld.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(ldTag), "LogsData.resource_logs is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(ldTag));
    CodedInputStream rl = ld.readBytes().newCodedInput();
    assertTrue(ld.isAtEnd(), "expected exactly one ResourceLogs");

    // ── parse ResourceLogs ────────────────────────────────────────────────
    boolean resourceFound = false;
    List<byte[]> scopeBlobs = new ArrayList<>();
    while (!rl.isAtEnd()) {
      int rlTag = rl.readTag();
      switch (WireFormat.getTagFieldNumber(rlTag)) {
        case 1:
          verifyResource(rl.readBytes().newCodedInput());
          resourceFound = true;
          break;
        case 2:
          scopeBlobs.add(rl.readBytes().toByteArray());
          break;
        default:
          rl.skipField(rlTag);
      }
    }
    assertTrue(resourceFound, "Resource must be present in ResourceLogs [" + caseName + "]");

    // ── verify ScopeLogs groups ───────────────────────────────────────────
    List<List<LogSpec>> scopeGroups = groupByScope(specs);
    assertEquals(
        scopeGroups.size(), scopeBlobs.size(), "ScopeLogs count mismatch [" + caseName + "]");

    for (int s = 0; s < scopeGroups.size(); s++) {
      List<LogSpec> group = scopeGroups.get(s);
      List<byte[]> logRecordBlobs =
          parseScopeLogs(
              CodedInputStream.newInstance(scopeBlobs.get(s)), group.get(0).scope, caseName);
      assertEquals(
          group.size(),
          logRecordBlobs.size(),
          "LogRecord count mismatch in scope " + s + " [" + caseName + "]");

      for (int r = 0; r < group.size(); r++) {
        verifyLogRecord(
            CodedInputStream.newInstance(logRecordBlobs.get(r)),
            group.get(r),
            resolveContext(spans, group.get(r)),
            caseName);
      }
    }
  }

  // ── span construction ─────────────────────────────────────────────────────

  private static List<DDSpan> buildSpans(List<LogSpec> specs) {
    int maxIndex = specs.stream().mapToInt(s -> s.spanContextIndex).max().orElse(-1);
    if (maxIndex < 0) {
      return new ArrayList<>();
    }
    List<DDSpan> spans = new ArrayList<>(maxIndex + 1);
    for (int i = 0; i <= maxIndex; i++) {
      final int idx = i;
      LogSpec refSpec =
          specs.stream().filter(s -> s.spanContextIndex == idx).findFirst().orElse(null);
      AgentSpan span;
      if (refSpec != null && refSpec.use128BitTraceId) {
        ExtractedContext parent128 =
            new ExtractedContext(
                TRACE_ID_128BIT,
                0L,
                PrioritySampling.UNSET,
                null,
                PropagationTags.factory().empty(),
                TracePropagationStyle.DATADOG);
        span = TRACER.startSpan("test", "test.op.128", parent128, 0L);
      } else {
        span = TRACER.startSpan("test", "test.op", 0L);
        span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DEFAULT);
      }
      span.finish(0L);
      spans.add((DDSpan) span);
    }
    return spans;
  }

  /**
   * Resolves the {@link AgentSpanContext} for a spec: the span's own context, or null if no
   * context.
   */
  @Nullable
  private static AgentSpanContext resolveContext(List<DDSpan> spans, LogSpec spec) {
    if (spec.spanContextIndex < 0) {
      return null;
    }
    DDSpan span = spans.get(spec.spanContextIndex);
    return span.context();
  }

  // ── grouping helper ───────────────────────────────────────────────────────

  private static List<List<LogSpec>> groupByScope(List<LogSpec> specs) {
    List<List<LogSpec>> groups = new ArrayList<>();
    OtelInstrumentationScope lastScope = null;
    for (LogSpec spec : specs) {
      if (!spec.scope.equals(lastScope)) {
        groups.add(new ArrayList<>());
        lastScope = spec.scope;
      }
      groups.get(groups.size() - 1).add(spec);
    }
    return groups;
  }

  // ── verification helpers ──────────────────────────────────────────────────

  /**
   * Parses a {@code Resource} message body and asserts it contains a {@code service.name}
   * attribute.
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
   * Parses a {@code ScopeLogs} message body, verifies its scope and schema_url, and returns the raw
   * bytes of each {@code log_records} entry.
   *
   * <pre>
   *   ScopeLogs { scope=1, log_records=2, schema_url=3 }
   * </pre>
   */
  private static List<byte[]> parseScopeLogs(
      CodedInputStream sl, OtelInstrumentationScope expectedScope, String caseName)
      throws IOException {
    List<byte[]> logRecords = new ArrayList<>();
    boolean scopeFound = false;
    String parsedSchemaUrl = null;
    while (!sl.isAtEnd()) {
      int tag = sl.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          verifyScope(sl.readBytes().newCodedInput(), expectedScope, caseName);
          scopeFound = true;
          break;
        case 2:
          logRecords.add(sl.readBytes().toByteArray());
          break;
        case 3:
          parsedSchemaUrl = sl.readString();
          break;
        default:
          sl.skipField(tag);
      }
    }
    assertTrue(scopeFound, "InstrumentationScope must be present in ScopeLogs [" + caseName + "]");
    String expectedSchemaUrl =
        expectedScope.getSchemaUrl() != null ? expectedScope.getSchemaUrl().toString() : null;
    assertEquals(expectedSchemaUrl, parsedSchemaUrl, "schema_url mismatch [" + caseName + "]");
    return logRecords;
  }

  /**
   * Parses an {@code InstrumentationScope} message body and verifies name and version.
   *
   * <pre>
   *   InstrumentationScope { name=1, version=2 }
   * </pre>
   */
  private static void verifyScope(
      CodedInputStream cs, OtelInstrumentationScope expected, String caseName) throws IOException {
    String parsedName = null;
    String parsedVersion = null;
    while (!cs.isAtEnd()) {
      int tag = cs.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          parsedName = cs.readString();
          break;
        case 2:
          parsedVersion = cs.readString();
          break;
        default:
          cs.skipField(tag);
      }
    }
    assertEquals(
        expected.getName().toString(), parsedName, "scope.name mismatch [" + caseName + "]");
    String expectedVersion =
        expected.getVersion() != null ? expected.getVersion().toString() : null;
    assertEquals(expectedVersion, parsedVersion, "scope.version mismatch [" + caseName + "]");
  }

  /**
   * Parses a {@code LogRecord} message body and verifies all fields against the spec.
   *
   * <pre>
   *   LogRecord { time_unix_nano=1, severity_number=2, severity_text=3, body=5,
   *               attributes=6, flags=8, trace_id=9, span_id=10,
   *               observed_time_unix_nano=11, event_name=12 }
   * </pre>
   */
  private static void verifyLogRecord(
      CodedInputStream lr, LogSpec spec, @Nullable AgentSpanContext ctx, String caseName)
      throws IOException {
    long parsedTimestamp = -1;
    long parsedObserved = -1;
    int parsedSeverityNumber = -1;
    String parsedSeverityText = null;
    String parsedBody = null;
    Set<String> attrKeys = new HashSet<>();
    int parsedFlags = 0;
    boolean flagsFound = false;
    byte[] parsedTraceId = null;
    byte[] parsedSpanId = null;
    String parsedEventName = null;

    while (!lr.isAtEnd()) {
      int tag = lr.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          parsedTimestamp = lr.readFixed64();
          break;
        case 2:
          parsedSeverityNumber = lr.readEnum();
          break;
        case 3:
          parsedSeverityText = lr.readString();
          break;
        case 5:
          parsedBody = readBodyString(lr.readBytes().newCodedInput());
          break;
        case 6:
          attrKeys.add(readKeyValueKey(lr.readBytes().newCodedInput()));
          break;
        case 8:
          parsedFlags = lr.readFixed32();
          flagsFound = true;
          break;
        case 9:
          parsedTraceId = lr.readBytes().toByteArray();
          break;
        case 10:
          parsedSpanId = lr.readBytes().toByteArray();
          break;
        case 11:
          parsedObserved = lr.readFixed64();
          break;
        case 12:
          parsedEventName = lr.readString();
          break;
        default:
          lr.skipField(tag);
      }
    }

    // ── timestamps ────────────────────────────────────────────────────────────
    assertEquals(
        spec.timestampNanos, parsedTimestamp, "time_unix_nano mismatch [" + caseName + "]");
    assertEquals(
        spec.observedNanos, parsedObserved, "observed_time_unix_nano mismatch [" + caseName + "]");

    // ── severity ──────────────────────────────────────────────────────────────
    assertEquals(
        spec.severityNumber, parsedSeverityNumber, "severity_number mismatch [" + caseName + "]");
    assertEquals(
        spec.severityText, parsedSeverityText, "severity_text mismatch [" + caseName + "]");

    // ── body ──────────────────────────────────────────────────────────────────
    assertEquals(spec.body, parsedBody, "body mismatch [" + caseName + "]");

    // ── event_name ────────────────────────────────────────────────────────────
    assertEquals(spec.eventName, parsedEventName, "event_name mismatch [" + caseName + "]");

    // ── attributes ────────────────────────────────────────────────────────────
    for (String key : spec.attrs.keySet()) {
      assertTrue(
          attrKeys.contains(key), "attribute '" + key + "' must be present [" + caseName + "]");
    }

    // ── span context fields (trace_id=9, span_id=10, flags=8) ─────────────────
    if (ctx == null) {
      assertNull(parsedTraceId, "trace_id must be absent when no span context [" + caseName + "]");
      assertNull(parsedSpanId, "span_id must be absent when no span context [" + caseName + "]");
      assertFalse(flagsFound, "flags must be absent when no span context [" + caseName + "]");
    } else {
      assertNotNull(parsedTraceId, "trace_id must be present [" + caseName + "]");
      assertEquals(16, parsedTraceId.length, "trace_id must be 16 bytes [" + caseName + "]");
      assertNotNull(parsedSpanId, "span_id must be present [" + caseName + "]");
      assertEquals(8, parsedSpanId.length, "span_id must be 8 bytes [" + caseName + "]");
      assertEquals(
          ctx.getSpanId(), readBigEndianLong(parsedSpanId), "span_id mismatch [" + caseName + "]");
      if (ctx.getSamplingPriority() > 0) {
        assertTrue(
            (parsedFlags & SAMPLED_TRACE_FLAG) != 0,
            "SAMPLED flag must be set in flags [" + caseName + "]");
      }
      if (spec.use128BitTraceId) {
        long highOrderBytes = readBigEndianLong(parsedTraceId);
        assertNotEquals(
            0L,
            highOrderBytes,
            "128-bit trace_id high-order bytes must be non-zero [" + caseName + "]");
      }
    }
  }

  // ── proto parsing helpers ─────────────────────────────────────────────────

  /**
   * Reads a {@code AnyValue} body and returns the string value from field 1 ({@code string_value}).
   */
  private static String readBodyString(CodedInputStream av) throws IOException {
    while (!av.isAtEnd()) {
      int tag = av.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) {
        return av.readString();
      }
      av.skipField(tag);
    }
    return null;
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

  /** Returns the {@link OtlpAttributeVisitor} type constant for a given value. */
  private static int attrType(Object value) {
    if (value instanceof String) return STRING_ATTRIBUTE;
    if (value instanceof Boolean) return BOOLEAN_ATTRIBUTE;
    if (value instanceof Long) return LONG_ATTRIBUTE;
    if (value instanceof Double) return DOUBLE_ATTRIBUTE;
    throw new IllegalArgumentException("Unsupported attribute type: " + value.getClass());
  }

  /** Reads a big-endian 64-bit value from the first 8 bytes of the given array. */
  private static long readBigEndianLong(byte[] bytes) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (bytes[i] & 0xFF);
    }
    return value;
  }
}

package datadog.trace.core.otlp.logs;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static datadog.trace.core.otlp.common.OtlpCommonJson.hexSpanId;
import static datadog.trace.core.otlp.common.OtlpCommonJson.hexTraceId;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.json.JsonMapper;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OtlpLogsJsonCollector}, parsing the produced JSON back with {@link JsonMapper}
 * to verify the OTLP JSON encoding: lowerCamelCase keys, hex trace/span ids, decimal-string
 * timestamps, and integer severity/flags.
 */
class OtlpLogsJsonCollectorTest {

  private static final CoreTracer TRACER = CoreTracer.builder().writer(new LoggingWriter()).build();
  private static final OtelInstrumentationScope SCOPE =
      new OtelInstrumentationScope("test.logger", null, null);

  @Test
  void emptyBatchProducesEmptyPayload() {
    OtlpLogsJsonCollector collector = OtlpLogsJsonCollector.INSTANCE;
    OtlpPayload payload = collector.collectLogs((visitor, interval) -> {}, 0);
    assertEquals(OtlpPayload.EMPTY, payload);
  }

  @Test
  void logRecordWithoutSpanContextHasNoTraceOrSpanId() throws IOException {
    OtlpLogRecord record = logRecord("hello", null, null);

    Map<String, Object> parsed = onlyLogRecord(collect(record, emptyMap()));

    assertEquals("hello", ((Map<String, Object>) parsed.get("body")).get("stringValue"));
    assertEquals("9", String.valueOf(parsed.get("severityNumber")));
    assertEquals("INFO", parsed.get("severityText"));
    assertTrue(parsed.get("timeUnixNano") instanceof String);
    assertTrue(parsed.get("observedTimeUnixNano") instanceof String);
    assertFalse(parsed.containsKey("traceId"));
    assertFalse(parsed.containsKey("spanId"));
    assertFalse(parsed.containsKey("flags"));
  }

  @Test
  void logRecordWithSpanContextHasHexTraceAndSpanIdAndSampledFlag() throws IOException {
    AgentSpan span = TRACER.startSpan("test", "test.op");
    span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DEFAULT);
    span.finish();
    DDSpan ddSpan = (DDSpan) span;

    OtlpLogRecord record = logRecord("with context", ddSpan.spanContext(), null);
    Map<String, Object> parsed = onlyLogRecord(collect(record, emptyMap()));

    assertEquals(hexTraceId(ddSpan.getTraceId()), parsed.get("traceId"));
    assertEquals(hexSpanId(ddSpan.getSpanId()), parsed.get("spanId"));
    assertEquals(1, ((Number) parsed.get("flags")).intValue(), "SAMPLED_TRACE_FLAG = 1");
  }

  @Test
  void logRecordWithEventNameHasEventNameField() throws IOException {
    OtlpLogRecord record = logRecord("clicked", null, "user.interaction");
    Map<String, Object> parsed = onlyLogRecord(collect(record, emptyMap()));

    assertEquals("user.interaction", parsed.get("eventName"));
  }

  @Test
  void logRecordWithAttributesWritesAttributesArray() throws IOException {
    OtlpLogRecord record = logRecord("tagged", null, null);
    Map<String, Object> parsed =
        onlyLogRecord(collect(record, Collections.singletonMap("service.name", "svc")));

    List<Object> attributes = (List<Object>) parsed.get("attributes");
    assertEquals(1, attributes.size());
    Map<String, Object> attr = (Map<String, Object>) attributes.get(0);
    assertEquals("service.name", attr.get("key"));
  }

  @Test
  void collectionAfterFailedAttributeWriteIsStillWellFormed() throws IOException {
    OtlpLogsJsonCollector collector = OtlpLogsJsonCollector.INSTANCE;

    // open the attributes array on the shared/reused collector, then blow up before it's closed
    assertThrows(
        RuntimeException.class,
        () ->
            collector.collectLogs(
                (visitor, interval) -> {
                  OtlpScopedLogsVisitor scoped = visitor.visitScopedLogs(SCOPE);
                  scoped.visitAttribute(STRING_ATTRIBUTE, "service.name", "svc");
                  throw new RuntimeException("boom");
                },
                0));

    // a subsequent, successful collection must still emit a well-formed attributes array
    OtlpLogRecord record = logRecord("tagged", null, null);
    Map<String, Object> parsed =
        onlyLogRecord(collect(record, Collections.singletonMap("service.name", "svc")));

    List<Object> attributes = (List<Object>) parsed.get("attributes");
    assertEquals(1, attributes.size());
    Map<String, Object> attr = (Map<String, Object>) attributes.get(0);
    assertEquals("service.name", attr.get("key"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static OtlpLogRecord logRecord(String body, AgentSpanContext ctx, String eventName) {
    return new OtlpLogRecord(
        SCOPE,
        1_700_000_000_000_000_000L,
        1_700_000_000_001_000_000L,
        9,
        "INFO",
        body,
        emptyMap(),
        ctx,
        eventName);
  }

  private static OtlpPayload collect(OtlpLogRecord record, Map<String, Object> attrs) {
    OtlpLogsJsonCollector collector = OtlpLogsJsonCollector.INSTANCE;
    return collector.collectLogs(
        (visitor, interval) -> {
          OtlpScopedLogsVisitor scoped = visitor.visitScopedLogs(SCOPE);
          for (Map.Entry<String, Object> attr : attrs.entrySet()) {
            scoped.visitAttribute(STRING_ATTRIBUTE, attr.getKey(), attr.getValue());
          }
          scoped.visitLogRecord(record);
        },
        0);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlyLogRecord(OtlpPayload payload) throws IOException {
    byte[] bytes = new byte[payload.getContentLength()];
    payload.getContent().get(bytes);
    String json = new String(bytes, StandardCharsets.UTF_8);
    Map<String, Object> root = JsonMapper.fromJsonToMap(json);

    List<Object> resourceLogs = (List<Object>) root.get("resourceLogs");
    Map<String, Object> resourceLog = (Map<String, Object>) resourceLogs.get(0);
    List<Object> scopeLogs = (List<Object>) resourceLog.get("scopeLogs");
    Map<String, Object> scopeLog = (Map<String, Object>) scopeLogs.get(0);
    List<Object> logRecords = (List<Object>) scopeLog.get("logRecords");
    assertEquals(1, logRecords.size());
    return (Map<String, Object>) logRecords.get(0);
  }
}

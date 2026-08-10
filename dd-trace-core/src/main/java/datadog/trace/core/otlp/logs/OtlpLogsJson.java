package datadog.trace.core.otlp.logs;

import static datadog.trace.core.otlp.common.OtlpCommonJson.hexSpanId;
import static datadog.trace.core.otlp.common.OtlpCommonJson.hexTraceId;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.SAMPLED_TRACE_FLAG;

import datadog.json.JsonWriter;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;

/** Provides writers for OpenTelemetry's "logs.proto" JSON encoding. */
public final class OtlpLogsJson {
  private OtlpLogsJson() {}

  /**
   * Writes a log record's non-attribute fields into the currently open {@code LogRecord} object.
   */
  public static void writeLogRecordFields(JsonWriter writer, OtlpLogRecord logRecord) {
    writer.name("timeUnixNano").value(Long.toString(logRecord.timestampNanos));
    writer.name("observedTimeUnixNano").value(Long.toString(logRecord.observedNanos));
    writer.name("severityNumber").value(logRecord.severityNumber);

    if (logRecord.severityText != null) {
      writer.name("severityText").value(logRecord.severityText);
    }

    if (logRecord.body != null) {
      writer.name("body").beginObject().name("stringValue").value(logRecord.body).endObject();
    }

    if (logRecord.spanContext != null) {
      writer.name("traceId").value(hexTraceId(logRecord.spanContext.getTraceId()));
      writer.name("spanId").value(hexSpanId(logRecord.spanContext.getSpanId()));
      if (logRecord.spanContext.getSamplingPriority() > 0) {
        writer.name("flags").value(SAMPLED_TRACE_FLAG);
      }
    }

    if (logRecord.eventName != null) {
      writer.name("eventName").value(logRecord.eventName);
    }
  }
}

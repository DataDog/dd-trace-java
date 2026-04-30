package datadog.trace.core.otlp.logs;

import static datadog.trace.core.otlp.common.OtlpCommonProto.I32_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.I64_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.VARINT_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.recordMessage;
import static datadog.trace.core.otlp.common.OtlpCommonProto.sizeVarInt;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI32;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI64;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeInstrumentationScope;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeString;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeVarInt;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.NO_TRACE_FLAGS;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.REMOTE_TRACE_FLAG;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.SAMPLED_TRACE_FLAG;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.writeSpanId;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.writeTraceId;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;

/** Provides optimized writers for OpenTelemetry's "logs.proto" wire protocol. */
public final class OtlpLogsProto {
  private OtlpLogsProto() {}

  /**
   * Records the first part of a scoped logs message where we know its nested log messages will
   * follow in one or more byte-arrays that add up to the given number of remaining bytes.
   */
  public static byte[] recordScopedLogsMessage(
      GrowableBuffer buf, OtelInstrumentationScope scope, int remainingBytes) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeInstrumentationScope(buf, scope);
    if (scope.getSchemaUrl() != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, scope.getSchemaUrl().getUtf8Bytes());
    }

    return recordMessage(buf, 2, remainingBytes);
  }

  /** Completes recording of a log record message and packs it into its own byte-array. */
  public static byte[] recordLogRecordMessage(GrowableBuffer buf, OtlpLogRecord logRecord) {

    writeTag(buf, 1, I64_WIRE_TYPE);
    writeI64(buf, logRecord.timestampNanos);

    writeTag(buf, 11, I64_WIRE_TYPE);
    writeI64(buf, logRecord.observedNanos);

    writeTag(buf, 2, VARINT_WIRE_TYPE);
    writeVarInt(buf, logRecord.severityNumber);

    if (logRecord.severityText != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, logRecord.severityText);
    }

    writeTag(buf, 5, LEN_WIRE_TYPE);
    byte[] bodyUtf8 = logRecord.body.getBytes(UTF_8);
    int bodySize = 1 + sizeVarInt(bodyUtf8.length) + bodyUtf8.length;
    writeVarInt(buf, bodySize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, bodyUtf8.length);
    buf.put(bodyUtf8);

    if (logRecord.spanContext != null) {
      writeTag(buf, 9, LEN_WIRE_TYPE);
      writeTraceId(buf, logRecord.spanContext.getTraceId());

      writeTag(buf, 10, LEN_WIRE_TYPE);
      writeSpanId(buf, logRecord.spanContext.getSpanId());

      int traceFlags = NO_TRACE_FLAGS;
      if (logRecord.spanContext.getSamplingPriority() > 0) {
        traceFlags |= SAMPLED_TRACE_FLAG;
      }
      if (logRecord.spanContext.isRemote()) {
        traceFlags |= REMOTE_TRACE_FLAG;
      }
      if (traceFlags != NO_TRACE_FLAGS) {
        writeTag(buf, 8, I32_WIRE_TYPE);
        writeI32(buf, traceFlags);
      }
    }

    if (logRecord.eventName != null) {
      writeTag(buf, 12, LEN_WIRE_TYPE);
      writeString(buf, logRecord.eventName);
    }

    return recordMessage(buf, 2);
  }
}

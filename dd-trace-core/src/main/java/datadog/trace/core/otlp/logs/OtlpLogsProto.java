package datadog.trace.core.otlp.logs;

import static datadog.trace.core.otlp.common.OtlpCommonProto.I32_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.I64_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.VARINT_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.sizeVarInt;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI32;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI64;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeInstrumentationScope;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeString;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeStringCached;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeVarInt;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.SAMPLED_TRACE_FLAG;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.writeSpanId;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.writeTraceId;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;
import datadog.trace.core.otlp.common.OtlpProtoBuffer;

/** Provides optimized writers for OpenTelemetry's "logs.proto" wire protocol. */
public final class OtlpLogsProto {
  private OtlpLogsProto() {}

  /** Records a scoped logs message after its nested log messages have been recorded. */
  public static int recordScopedLogsMessage(
      GrowableBuffer buf,
      OtelInstrumentationScope scope,
      int nestedLogBytes,
      OtlpProtoBuffer protobuf) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeInstrumentationScope(buf, scope);
    if (scope.getSchemaUrl() != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, scope.getSchemaUrl().getUtf8Bytes());
    }

    return protobuf.recordMessage(buf, 2, nestedLogBytes);
  }

  /** Records a log message. */
  public static int recordLogRecordMessage(
      GrowableBuffer buf, OtlpLogRecord logRecord, OtlpProtoBuffer protobuf) {

    writeTag(buf, 1, I64_WIRE_TYPE);
    writeI64(buf, logRecord.timestampNanos);

    writeTag(buf, 11, I64_WIRE_TYPE);
    writeI64(buf, logRecord.observedNanos);

    writeTag(buf, 2, VARINT_WIRE_TYPE);
    writeVarInt(buf, logRecord.severityNumber);

    if (logRecord.severityText != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeStringCached(buf, logRecord.severityText);
    }

    if (logRecord.body != null) {
      writeTag(buf, 5, LEN_WIRE_TYPE);
      byte[] bodyUtf8 = logRecord.body.getBytes(UTF_8);
      int bodySize = 1 + sizeVarInt(bodyUtf8.length) + bodyUtf8.length;
      writeVarInt(buf, bodySize);
      writeTag(buf, 1, LEN_WIRE_TYPE);
      writeVarInt(buf, bodyUtf8.length);
      buf.put(bodyUtf8);
    }

    if (logRecord.spanContext != null) {
      writeTag(buf, 9, LEN_WIRE_TYPE);
      writeTraceId(buf, logRecord.spanContext.getTraceId());

      writeTag(buf, 10, LEN_WIRE_TYPE);
      writeSpanId(buf, logRecord.spanContext.getSpanId());

      if (logRecord.spanContext.getSamplingPriority() > 0) {
        writeTag(buf, 8, I32_WIRE_TYPE);
        writeI32(buf, SAMPLED_TRACE_FLAG);
      }
    }

    if (logRecord.eventName != null) {
      writeTag(buf, 12, LEN_WIRE_TYPE);
      writeStringCached(buf, logRecord.eventName);
    }

    return protobuf.recordMessage(buf, 2);
  }
}

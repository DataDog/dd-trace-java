package datadog.trace.core.otlp.logs;

import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpResourceProto.RESOURCE_MESSAGE;
import static datadog.trace.core.otlp.logs.OtlpLogsProto.recordLogRecordMessage;
import static datadog.trace.core.otlp.logs.OtlpLogsProto.recordScopedLogsMessage;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.logs.data.OtelLogRecordProcessor;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;
import datadog.trace.bootstrap.otlp.logs.OtlpLogsVisitor;
import datadog.trace.bootstrap.otlp.logs.OtlpScopedLogsVisitor;
import datadog.trace.core.otlp.common.OtlpCommonProto;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpProtoBuffer;
import java.util.function.ObjIntConsumer;

/**
 * Collects OpenTelemetry logs and marshals them into a chunked 'logs.proto' payload.
 *
 * <p>This collector is designed to be called by a single thread. To minimize allocations each
 * collection returns a payload only to be used by the calling thread until the next collection.
 * (The payload should be copied before passing it onto another thread.)
 *
 * <p>We use a single temporary buffer to prepare message chunks at different nesting levels. First
 * we chunk all log records for a given scope. Once the scope is complete we add the first part of
 * the scoped logs message and all its chunked log records to the payload. Once all the logs data
 * has been chunked we add the enclosing resource logs message to the start of the payload.
 */
public final class OtlpLogsProtoCollector extends OtlpLogsCollector
    implements OtlpLogsVisitor, OtlpScopedLogsVisitor {

  public static final OtlpLogsProtoCollector INSTANCE = new OtlpLogsProtoCollector();

  private static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";

  private final GrowableBuffer buf = new GrowableBuffer(512);
  private final OtlpProtoBuffer protobuf = new OtlpProtoBuffer(8192);

  // total number of chunked bytes at different nesting levels
  private int payloadBytes;
  private int scopedBytes;

  private OtelInstrumentationScope currentScope;

  private OtlpLogsProtoCollector() {}

  /**
   * Collects OpenTelemetry logs and marshals them into a chunked payload.
   *
   * <p>This payload is only valid for the calling thread until the next collection.
   */
  @Override
  public OtlpPayload waitForLogs(int intervalMillis) {
    return collectLogs(OtelLogRecordProcessor.INSTANCE::waitForLogs, intervalMillis);
  }

  OtlpPayload collectLogs(ObjIntConsumer<OtlpLogsVisitor> processor, int intervalMillis) {
    start();
    try {
      processor.accept(this, intervalMillis);
      return completePayload();
    } finally {
      stop();
    }
  }

  /** Prepare temporary elements to collect logs data. */
  private void start() {

    // remove stale entries from caches
    OtlpCommonProto.recalibrateCaches();
  }

  /** Cleanup elements used to collect logs data. */
  private void stop() {
    buf.reset();
    protobuf.reset();

    payloadBytes = 0;
    scopedBytes = 0;

    currentScope = null;
  }

  @Override
  public OtlpScopedLogsVisitor visitScopedLogs(OtelInstrumentationScope scope) {
    if (currentScope != null) {
      completeScope();
    }
    currentScope = scope;
    return this;
  }

  @Override
  public void visitLogRecord(OtlpLogRecord logRecord) {
    scopedBytes += recordLogRecordMessage(buf, logRecord, protobuf);
  }

  @Override
  public void visitAttribute(int type, String key, Object value) {
    // add attribute to the log record currently being collected
    writeTag(buf, 6, LEN_WIRE_TYPE);
    writeAttribute(buf, type, key, value);
  }

  // called once we've processed all scopes and log record messages
  private OtlpPayload completePayload() {
    if (currentScope != null) {
      completeScope();
    }

    if (payloadBytes == 0) {
      return OtlpPayload.EMPTY;
    }

    // prepend the canned resource chunk
    payloadBytes += protobuf.recordMessage(RESOURCE_MESSAGE);

    // finally prepend the total length of all collected chunks
    protobuf.recordMessage(buf, 1, payloadBytes);
    return protobuf.toPayload();
  }

  // called once we've processed all logs in a specific scope
  private void completeScope() {

    // add scoped logs message prefix to its nested chunks and promote to payload
    if (scopedBytes > 0) {
      payloadBytes += recordScopedLogsMessage(buf, currentScope, scopedBytes, protobuf);
    }

    // reset temporary elements for next scope
    currentScope = null;
    scopedBytes = 0;
  }
}

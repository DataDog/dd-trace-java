package datadog.trace.core.otlp.logs;

import static datadog.trace.core.otlp.common.OtlpCommonJson.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonJson.writeScopeAndSchema;
import static datadog.trace.core.otlp.common.OtlpPayload.JSON_CONTENT_TYPE;
import static datadog.trace.core.otlp.common.OtlpResourceJson.RESOURCE_FRAGMENT;
import static datadog.trace.core.otlp.logs.OtlpLogsJson.writeLogRecordFields;

import datadog.json.JsonWriter;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.logs.data.OtelLogRecordProcessor;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;
import datadog.trace.bootstrap.otlp.logs.OtlpLogsVisitor;
import datadog.trace.bootstrap.otlp.logs.OtlpScopedLogsVisitor;
import datadog.trace.core.otlp.common.LazyJsonArray;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.nio.ByteBuffer;
import java.util.function.ObjIntConsumer;

/**
 * Collects OpenTelemetry logs and marshals them into a 'logs.proto' JSON payload.
 *
 * <p>This collector is designed to be called by a single thread. To minimize allocations each
 * collection returns a payload only to be used by the calling thread until the next collection.
 * (The payload should be copied before passing it onto another thread.)
 *
 * <p>Attributes for a log record are written directly into the currently open log record object as
 * {@code visitAttribute} is called, then {@code visitLogRecord} writes the rest of its fields and
 * closes it.
 */
public final class OtlpLogsJsonCollector extends OtlpLogsCollector
    implements OtlpLogsVisitor, OtlpScopedLogsVisitor {

  public static final OtlpLogsJsonCollector INSTANCE = new OtlpLogsJsonCollector();

  private JsonWriter writer;
  private boolean anyLogRecordWritten;
  private boolean logRecordStarted;
  private int logRecordCount;

  private final LazyJsonArray attributesArray = new LazyJsonArray();

  private OtelInstrumentationScope currentScope;

  private OtlpLogsJsonCollector() {}

  /**
   * Collects OpenTelemetry logs and marshals them into a JSON payload.
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
    logRecordCount = 0;

    writer = new JsonWriter();
    writer.beginObject();
    writer.name("resourceLogs").beginArray();
    writer.beginObject();
    writer.name("resource").jsonValue(RESOURCE_FRAGMENT);
    writer.name("scopeLogs").beginArray();
  }

  /** Cleanup elements used to collect logs data. */
  private void stop() {
    attributesArray.reset();

    anyLogRecordWritten = false;

    writer = null;

    logRecordStarted = false;

    currentScope = null;
  }

  @Override
  public int getLogRecordCount() {
    return logRecordCount;
  }

  @Override
  public OtlpScopedLogsVisitor visitScopedLogs(OtelInstrumentationScope scope) {
    if (currentScope != null) {
      completeScope();
    }
    currentScope = scope;

    writer.beginObject();
    writeScopeAndSchema(writer, scope);
    writer.name("logRecords").beginArray();

    return this;
  }

  @Override
  public void visitAttribute(int type, String key, Object value) {
    // add attribute to the log record currently being collected
    ensureLogRecordStarted();
    attributesArray.ensureOpen(writer, "attributes");
    writeAttribute(writer, type, key, value);
  }

  @Override
  public void visitLogRecord(OtlpLogRecord logRecord) {
    ensureLogRecordStarted();
    attributesArray.closeIfOpen(writer);

    writeLogRecordFields(writer, logRecord);

    writer.endObject(); // log record

    logRecordStarted = false;
    anyLogRecordWritten = true;
    logRecordCount++;
  }

  // opens the log record object on first attribute or value written for it
  private void ensureLogRecordStarted() {
    if (!logRecordStarted) {
      writer.beginObject();
      logRecordStarted = true;
    }
  }

  // called once we've processed all scopes and log record messages
  private OtlpPayload completePayload() {
    if (currentScope != null) {
      completeScope();
    }

    writer.endArray(); // scopeLogs
    writer.endObject(); // resourceLogs[0]
    writer.endArray(); // resourceLogs
    writer.endObject(); // root

    if (!anyLogRecordWritten) {
      return OtlpPayload.EMPTY;
    }

    byte[] bytes = writer.toByteArray();
    return new OtlpPayload(ByteBuffer.wrap(bytes), JSON_CONTENT_TYPE);
  }

  // called once we've processed all log records in a specific scope
  private void completeScope() {
    writer.endArray(); // logRecords
    writer.endObject(); // scopeLogs[0]

    // reset temporary elements for next scope
    currentScope = null;
  }
}

package com.datadog.debugger.agent;

import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotHelper;
import com.datadog.debugger.util.SnapshotSummary;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serializes snapshots in Json using Moshi */
public class JsonSnapshotSerializer implements DebuggerContext.SnapshotSerializer {
  private static final Logger LOG = LoggerFactory.getLogger(JsonSnapshotSerializer.class);
  private static final String DD_TRACE_ID = "dd.trace_id";
  private static final String DD_SPAN_ID = "dd.span_id";
  private static final JsonAdapter<IntakeRequest> ADAPTER =
      MoshiHelper.createMoshiSnapshot().adapter(IntakeRequest.class);
  private static final JsonAdapter<Snapshot.CapturedValue> VALUE_ADAPTER =
      new MoshiSnapshotHelper.CapturedValueAdapter();

  @Override
  public String serializeSnapshot(String serviceName, Snapshot snapshot) {
    IntakeRequest request = new IntakeRequest(serviceName, new DebuggerIntakeRequestData(snapshot));
    handleCorrelationFields(snapshot, request);
    handleDuration(snapshot, request);
    handlerLogger(snapshot, request);
    return ADAPTER.toJson(request);
  }

  @Override
  public String serializeValue(Snapshot.CapturedValue value) {
    return VALUE_ADAPTER.toJson(value);
  }

  private void handlerLogger(Snapshot snapshot, IntakeRequest request) {
    request.loggerName = snapshot.getProbe().getLocation().getType();
    request.loggerMethod = snapshot.getProbe().getLocation().getMethod();
    request.loggerVersion = snapshot.retrieveVersion();
    request.loggerThreadId = snapshot.retrieveThread().getId();
    request.loggerThreadName = snapshot.retrieveThread().getName();
  }

  private void handleDuration(Snapshot snapshot, IntakeRequest request) {
    request.duration = snapshot.retrieveDuration();
  }

  private void handleCorrelationFields(Snapshot snapshot, IntakeRequest request) {
    Snapshot.CapturedContext entry = snapshot.getCaptures().getEntry();
    if (entry != null) {
      addTraceSpanId(entry, request);
      removeTraceSpanId(entry);
    }
    if (snapshot.getCaptures().getLines() != null) {
      for (Snapshot.CapturedContext context : snapshot.getCaptures().getLines().values()) {
        addTraceSpanId(context, request);
        removeTraceSpanId(context);
      }
    }
    removeTraceSpanId(snapshot.getCaptures().getReturn());
  }

  private void removeTraceSpanId(Snapshot.CapturedContext context) {
    if (context == null) {
      return;
    }
    Map<String, Snapshot.CapturedValue> fields = context.getFields();
    if (fields == null) {
      return;
    }
    fields.remove(DD_TRACE_ID);
    fields.remove(DD_SPAN_ID);
  }

  private void addTraceSpanId(Snapshot.CapturedContext context, IntakeRequest request) {
    Map<String, Snapshot.CapturedValue> fields = context.getFields();
    if (fields == null) {
      return;
    }
    request.traceId = extractCorrelationField(fields, DD_TRACE_ID);
    request.spanId = extractCorrelationField(fields, DD_SPAN_ID);
  }

  private String extractCorrelationField(
      Map<String, Snapshot.CapturedValue> fields, String fieldName) {
    Snapshot.CapturedValue fieldValue = fields.get(fieldName);
    if (fieldValue != null) {
      return getValue(fieldValue, fieldName);
    }
    return null;
  }

  public static String getValue(Snapshot.CapturedValue capturedValue, String name) {
    if (capturedValue != null) {
      try {
        Snapshot.CapturedValue deserializedValue =
            VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
        return String.valueOf(deserializedValue.getValue());
      } catch (Exception e) {
        LOG.warn("Cannot deserialize " + name, e);
      }
    }
    return null;
  }

  public static class IntakeRequest {
    private final String service;
    private final DebuggerIntakeRequestData debugger;
    private final String ddsource = "dd_debugger";
    private final String message;

    private final String ddtags;

    @Json(name = "dd.trace_id")
    private String traceId;

    @Json(name = "dd.span_id")
    private String spanId;

    private long duration;

    private long timestamp;

    @Json(name = "logger.name")
    private String loggerName;

    @Json(name = "logger.method")
    private String loggerMethod;

    @Json(name = "logger.version")
    private int loggerVersion;

    @Json(name = "logger.thread_id")
    private long loggerThreadId;

    @Json(name = "logger.thread_name")
    private String loggerThreadName;

    public IntakeRequest(String service, DebuggerIntakeRequestData debugger) {
      this.service = service;
      this.debugger = debugger;
      this.message = SnapshotSummary.formatMessage(debugger.snapshot);
      this.ddtags = debugger.snapshot.getProbe().getTags();
      this.timestamp = debugger.snapshot.getTimestamp();
    }

    public static String concatTags(String... tags) {
      StringBuilder sb = new StringBuilder();
      for (String tag : tags) {
        sb.append(tag);
        sb.append(",");
      }
      return sb.substring(0, sb.length() - 1); // Remove last comma
    }

    public String getService() {
      return service;
    }

    public DebuggerIntakeRequestData getDebugger() {
      return debugger;
    }

    public String getDdsource() {
      return ddsource;
    }

    public String getMessage() {
      return message;
    }

    public String getTraceId() {
      return traceId;
    }

    public String getSpanId() {
      return spanId;
    }

    public long getTimestamp() {
      return timestamp;
    }
  }

  public static class DebuggerIntakeRequestData {
    private final Snapshot snapshot;

    public DebuggerIntakeRequestData(Snapshot snapshot) {
      this.snapshot = snapshot;
    }

    public Snapshot getSnapshot() {
      return snapshot;
    }
  }
}

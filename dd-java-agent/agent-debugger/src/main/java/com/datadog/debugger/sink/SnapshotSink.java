package com.datadog.debugger.sink;

import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.SnapshotSummary;
import com.datadog.debugger.util.TagsHelper;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.Snapshot.CapturedValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects snapshots that needs to be sent to the backend */
public class SnapshotSink {

  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerSink.class);
  private static final JsonAdapter<IntakeRequest> ADAPTER =
      MoshiHelper.createMoshiSnapshot().adapter(IntakeRequest.class);
  public static final String DD_TRACE_ID = "dd.trace_id";
  public static final String DD_SPAN_ID = "dd.span_id";
  private static final int CAPACITY = 1000;

  private final BlockingQueue<Snapshot> snapshots = new ArrayBlockingQueue<>(CAPACITY);
  private final String serviceName;
  private final int batchSize;

  public SnapshotSink(Config config) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.batchSize = config.getDebuggerUploadBatchSize();
  }

  public List<String> getSerializedSnapshots() {
    List<Snapshot> snapshots = new ArrayList<>();
    this.snapshots.drainTo(snapshots, batchSize);
    List<String> serializedSnapshots = new ArrayList<>();
    for (Snapshot snapshot : snapshots) {
      try {
        serializedSnapshots.add(serializeSnapshot(snapshot));
        LOGGER.debug("Sending snapshot for probe: {}", snapshot.getProbe().getId());
      } catch (Exception e) {
        ExceptionHelper.logException(LOGGER, e, "Error during snapshot serialization:");
      }
    }
    return serializedSnapshots;
  }

  public List<Snapshot> getSnapshots() {
    List<Snapshot> snapshots = new ArrayList<>();
    this.snapshots.drainTo(snapshots, batchSize);
    return snapshots;
  }

  public long remainingCapacity() {
    return snapshots.remainingCapacity();
  }

  public boolean offer(Snapshot snapshot) {
    return snapshots.offer(snapshot);
  }

  String serializeSnapshot(Snapshot snapshot) {
    IntakeRequest request = new IntakeRequest(serviceName, new DebuggerIntakeRequestData(snapshot));
    handleCorrelationFields(snapshot, request);
    handleDuration(snapshot, request);
    handlerLogger(snapshot, request);
    return ADAPTER.toJson(request);
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
    Map<String, CapturedValue> fields = context.getFields();
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
    Snapshot.CapturedValue dd_trace_id = fields.get(DD_TRACE_ID);
    if (dd_trace_id != null) {
      request.traceId = dd_trace_id.getValue();
    }
    Snapshot.CapturedValue dd_span_id = fields.get(DD_SPAN_ID);
    if (dd_span_id != null) {
      request.spanId = dd_span_id.getValue();
    }
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

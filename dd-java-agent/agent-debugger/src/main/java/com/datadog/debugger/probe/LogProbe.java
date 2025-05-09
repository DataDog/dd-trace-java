package com.datadog.debugger.probe;

import static com.datadog.debugger.probe.LogProbe.Capture.toLimits;
import static java.lang.String.format;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.agent.StringTemplateBuilder;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.CapturedContextInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.WeakIdentityHashMap;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Types;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CorrelationAccess;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores definition of a log probe */
public class LogProbe extends ProbeDefinition implements Sampled {
  private static final Logger LOGGER = LoggerFactory.getLogger(LogProbe.class);
  private static final Limits LIMITS = new Limits(1, 3, 8192, 5);
  private static final int LOG_MSG_LIMIT = 8192;

  public static final int CAPTURING_PROBE_BUDGET = 10;
  public static final int NON_CAPTURING_PROBE_BUDGET = 1000;

  /** Stores part of a templated message either a str or an expression */
  public static class Segment {
    private final String str;
    private final ValueScript parsedExpr;

    public Segment(String str) {
      this.str = str;
      this.parsedExpr = null;
    }

    public Segment(ValueScript valueScript) {
      this.str = null;
      this.parsedExpr = valueScript;
    }

    public String getStr() {
      return str;
    }

    public String getExpr() {
      return parsedExpr != null ? parsedExpr.getDsl() : null;
    }

    public ValueScript getParsedExpr() {
      return parsedExpr;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Segment segment = (Segment) o;
      return Objects.equals(str, segment.str) && Objects.equals(parsedExpr, segment.parsedExpr);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(str, parsedExpr);
    }

    @Generated
    @Override
    public String toString() {
      return "Segment{" + "str='" + str + '\'' + ", parsedExr=" + parsedExpr + '}';
    }

    public static class SegmentJsonAdapter extends JsonAdapter<Segment> {
      private final ValueScript.ValueScriptAdapter valueScriptAdapter =
          new ValueScript.ValueScriptAdapter();

      @Override
      public Segment fromJson(JsonReader reader) throws IOException {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
          throw new IOException("Invalid Segment format, expect Json object");
        }
        JsonReader peekReader = reader.peekJson();
        peekReader.beginObject();
        Segment segment;
        String fieldName = peekReader.nextName();
        if ("str".equals(fieldName)) {
          reader.beginObject();
          reader.nextName(); // consume str
          segment = new Segment(reader.nextString());
          reader.endObject();
        } else {
          segment = new Segment(valueScriptAdapter.fromJson(reader));
        }
        return segment;
      }

      @Override
      public void toJson(JsonWriter writer, Segment value) throws IOException {
        if (value == null) {
          writer.nullValue();
          return;
        }
        if (value.str != null) {
          writer.beginObject();
          writer.name("str");
          writer.value(value.str);
          writer.endObject();
        } else {
          valueScriptAdapter.toJson(writer, value.parsedExpr);
        }
      }
    }
  }

  /** Stores capture limits */
  public static final class Capture {
    private int maxReferenceDepth = Limits.DEFAULT_REFERENCE_DEPTH;
    private int maxCollectionSize = Limits.DEFAULT_COLLECTION_SIZE;
    private int maxLength = Limits.DEFAULT_LENGTH;
    private int maxFieldCount = Limits.DEFAULT_FIELD_COUNT;

    private Capture() {
      // for Moshi to assign default values
    }

    public Capture(int maxReferenceDepth, int maxCollectionSize, int maxLength, int maxFieldCount) {
      this.maxReferenceDepth = maxReferenceDepth;
      this.maxCollectionSize = maxCollectionSize;
      this.maxLength = maxLength;
      this.maxFieldCount = maxFieldCount;
    }

    public int getMaxReferenceDepth() {
      return maxReferenceDepth;
    }

    public int getMaxCollectionSize() {
      return maxCollectionSize;
    }

    public int getMaxLength() {
      return maxLength;
    }

    public int getMaxFieldCount() {
      return maxFieldCount;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Capture capture = (Capture) o;
      return maxReferenceDepth == capture.maxReferenceDepth
          && maxCollectionSize == capture.maxCollectionSize
          && maxLength == capture.maxLength
          && maxFieldCount == capture.maxFieldCount;
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(maxReferenceDepth, maxCollectionSize, maxLength, maxFieldCount);
    }

    @Generated
    @Override
    public String toString() {
      return "Capture{"
          + "maxReferenceDepth="
          + maxReferenceDepth
          + ", maxCollectionSize="
          + maxCollectionSize
          + ", maxLength="
          + maxLength
          + ", maxFieldCount="
          + maxFieldCount
          + '}';
    }

    public static Limits toLimits(Capture capture) {
      if (capture == null) {
        return Limits.DEFAULT;
      }
      return new Limits(
          capture.maxReferenceDepth,
          capture.maxCollectionSize,
          capture.maxLength,
          capture.maxFieldCount);
    }
  }

  /** Stores sampling configuration */
  public static final class Sampling extends com.datadog.debugger.probe.Sampling {
    private double snapshotsPerSecond;

    public Sampling(double snapshotsPerSecond) {
      this.snapshotsPerSecond = snapshotsPerSecond;
    }

    public double getSnapshotsPerSecond() {
      return snapshotsPerSecond;
    }

    @Override
    public double getEventsPerSecond() {
      return snapshotsPerSecond;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Sampling sampling = (Sampling) o;
      return Double.compare(sampling.snapshotsPerSecond, snapshotsPerSecond) == 0;
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(snapshotsPerSecond);
    }

    @Generated
    @Override
    public String toString() {
      return "Sampling{" + "snapshotsPerSecond=" + snapshotsPerSecond + '}';
    }
  }

  private final String template;
  private final List<Segment> segments;
  private final boolean captureSnapshot;
  private transient List<ValueScript> watches;

  @Json(name = "when")
  private final ProbeCondition probeCondition;

  private final Capture capture;
  private final Sampling sampling;
  private transient Consumer<Snapshot> snapshotProcessor;
  protected transient Map<DDTraceId, AtomicInteger> budget =
      Collections.synchronizedMap(new WeakIdentityHashMap<>());

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public LogProbe() {
    this(
        LANGUAGE,
        null,
        Tag.fromStrings(null),
        null,
        MethodLocation.DEFAULT,
        null,
        null,
        false,
        null,
        null,
        null);
  }

  public LogProbe(
      String language,
      ProbeId probeId,
      String[] tagStrs,
      Where where,
      MethodLocation evaluateAt,
      String template,
      List<Segment> segments,
      boolean captureSnapshot,
      ProbeCondition probeCondition,
      Capture capture,
      Sampling sampling) {
    this(
        language,
        probeId,
        Tag.fromStrings(tagStrs),
        where,
        evaluateAt,
        template,
        segments,
        captureSnapshot,
        probeCondition,
        capture,
        sampling);
  }

  private LogProbe(
      String language,
      ProbeId probeId,
      Tag[] tags,
      Where where,
      MethodLocation evaluateAt,
      String template,
      List<Segment> segments,
      boolean captureSnapshot,
      ProbeCondition probeCondition,
      Capture capture,
      Sampling sampling) {
    super(language, probeId, tags, where, evaluateAt);
    this.template = template;
    this.segments = segments;
    this.captureSnapshot = captureSnapshot;
    this.probeCondition = probeCondition;
    this.capture = capture;
    this.sampling = sampling;
  }

  public LogProbe(LogProbe.Builder builder) {
    this(
        builder.language,
        builder.probeId,
        builder.tagStrs,
        builder.where,
        builder.evaluateAt,
        builder.template,
        builder.segments,
        builder.captureSnapshot,
        builder.probeCondition,
        builder.capture,
        builder.sampling);
    this.snapshotProcessor = builder.snapshotProcessor;
  }

  @SuppressForbidden // String#split(String)
  private static List<ValueScript> parseWatchesFromTags(Tag[] tags) {
    if (tags == null || tags.length == 0) {
      return Collections.emptyList();
    }
    List<ValueScript> result = new ArrayList<>();
    for (Tag tag : tags) {
      if ("dd_watches_dsl".equals(tag.getKey())) {
        String ddWatches = tag.getValue();
        // this for POC only, parsing is not robust!
        String[] splitWatches = ddWatches.split(",");
        for (String watchDef : splitWatches) {
          // remove curly braces
          String refPath = watchDef.substring(1, watchDef.length() - 1);
          result.add(new ValueScript(ValueScript.parseRefPath(refPath), refPath));
        }
      } else if ("dd_watches_json".equals(tag.getKey())) {
        String json = tag.getValue();
        try {
          ParameterizedType type = Types.newParameterizedType(List.class, ValueScript.class);
          result.addAll(
              MoshiHelper.createMoshiWatches().<List<ValueScript>>adapter(type).fromJson(json));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
    return result;
  }

  public LogProbe copy() {
    return new LogProbe(
        language,
        new ProbeId(id, version),
        tags,
        where,
        evaluateAt,
        template,
        segments,
        captureSnapshot,
        probeCondition,
        capture,
        sampling);
  }

  public String getTemplate() {
    return template;
  }

  public List<Segment> getSegments() {
    return segments;
  }

  public boolean isCaptureSnapshot() {
    return captureSnapshot;
  }

  public List<ValueScript> getWatches() {
    return watches;
  }

  public ProbeCondition getProbeCondition() {
    return probeCondition;
  }

  public Capture getCapture() {
    return capture;
  }

  public Sampling getSampling() {
    return sampling;
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<ProbeId> probeIds) {
    // only capture entry values if explicitly not at Exit. By default, we are using evaluateAt=EXIT
    boolean captureEntry = getEvaluateAt() != MethodLocation.EXIT;
    return new CapturedContextInstrumentor(
            this,
            methodInfo,
            diagnostics,
            probeIds,
            isCaptureSnapshot(),
            captureEntry,
            toLimits(getCapture()))
        .instrument();
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {
    if (!(status instanceof LogStatus)) {
      throw new IllegalStateException("Invalid status: " + status.getClass());
    }

    LogStatus logStatus = (LogStatus) status;
    if (!hasCondition()) {
      // sample when no condition associated
      sample(logStatus, methodLocation);
    }
    logStatus.setCondition(evaluateCondition(context, logStatus));
    CapturedContext.CapturedThrowable throwable = context.getCapturedThrowable();
    if (logStatus.hasConditionErrors() && throwable != null) {
      logStatus.addError(
          new EvaluationError(
              "uncaught exception", throwable.getType() + ": " + throwable.getMessage()));
    }
    if (hasCondition() && (logStatus.getCondition() || logStatus.hasConditionErrors())) {
      // sample if probe has condition and condition is true or has error
      sample(logStatus, methodLocation);
    }
    processMsgTemplate(context, logStatus);
    processWatches(context, logStatus);
  }

  private void processMsgTemplate(CapturedContext context, LogStatus logStatus) {
    if (!logStatus.isSampled() || !logStatus.getCondition()) {
      return;
    }
    StringTemplateBuilder logMessageBuilder = new StringTemplateBuilder(segments, LIMITS);
    String msg = logMessageBuilder.evaluate(context, logStatus);
    if (msg != null && msg.length() > LOG_MSG_LIMIT) {
      StringBuilder sb = new StringBuilder(LOG_MSG_LIMIT + 3);
      sb.append(msg, 0, LOG_MSG_LIMIT);
      sb.append("...");
      msg = sb.toString();
    }
    logStatus.setMessage(msg);
  }

  private void processWatches(CapturedContext context, LogStatus logStatus) {
    if (watches == null) {
      watches = parseWatchesFromTags(tags);
    }
    if (watches.isEmpty()) {
      return;
    }
    if (!logStatus.isSampled()) {
      return;
    }
    for (ValueScript watch : watches) {
      try {
        Value<?> result = watch.execute(context);
        if (result.isUndefined()) {
          throw new EvaluationException("UNDEFINED", watch.getDsl());
        }
        if (result.isNull()) {
          context.addWatch(
              CapturedContext.CapturedValue.of(watch.getDsl(), Object.class.getTypeName(), null));
        } else {
          context.addWatch(
              CapturedContext.CapturedValue.of(
                  watch.getDsl(), Object.class.getTypeName(), result.getValue()));
        }
      } catch (EvaluationException ex) {
        logStatus.addError(new EvaluationError(ex.getExpr(), ex.getMessage()));
        logStatus.setLogTemplateErrors(true);
      }
    }
  }

  private void sample(LogStatus logStatus, MethodLocation methodLocation) {
    if (logStatus.isForceSampling()) {
      return;
    }
    // sample only once and when we need to evaluate
    if (!MethodLocation.isSame(methodLocation, evaluateAt)) {
      return;
    }
    boolean sampled =
        !logStatus.getDebugSessionStatus().isDisabled() && ProbeRateLimiter.tryProbe(id);
    logStatus.setSampled(sampled);
    if (!sampled) {
      DebuggerAgent.getSink()
          .skipSnapshot(
              id,
              logStatus.getDebugSessionStatus().isDisabled()
                  ? DebuggerContext.SkipCause.DEBUG_SESSION_DISABLED
                  : DebuggerContext.SkipCause.RATE);
    }
  }

  private boolean evaluateCondition(CapturedContext capture, LogStatus status) {
    if (probeCondition == null) {
      return true;
    }
    try {
      if (!probeCondition.execute(capture)) {
        return false;
      }
    } catch (EvaluationException ex) {
      status.addError(new EvaluationError(ex.getExpr(), ex.getMessage()));
      status.setConditionErrors(true);
      return false;
    }
    return true;
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions) {
    Snapshot snapshot = createSnapshot();
    boolean shouldCommit = fillSnapshot(entryContext, exitContext, caughtExceptions, snapshot);
    DebuggerSink sink = DebuggerAgent.getSink();
    if (shouldCommit) {
      incrementBudget();
      if (inBudget()) {
        commitSnapshot(snapshot, sink);
        if (snapshotProcessor != null) {
          snapshotProcessor.accept(snapshot);
        }
      } else {
        sink.skipSnapshot(id, DebuggerContext.SkipCause.BUDGET);
      }
    } else {
      sink.skipSnapshot(id, DebuggerContext.SkipCause.CONDITION);
    }
  }

  protected Snapshot createSnapshot() {
    int maxDepth = capture != null ? capture.maxReferenceDepth : -1;
    return new Snapshot(Thread.currentThread(), this, maxDepth);
  }

  protected boolean fillSnapshot(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions,
      Snapshot snapshot) {
    LogStatus entryStatus = convertStatus(entryContext.getStatus(probeId.getEncodedId()));
    LogStatus exitStatus = convertStatus(exitContext.getStatus(probeId.getEncodedId()));
    String message = null;
    switch (evaluateAt) {
      case ENTRY:
      case DEFAULT:
        message = entryStatus.getMessage();
        break;
      case EXIT:
        message = exitStatus.getMessage();
        break;
    }
    boolean shouldCommit = false;
    if (entryStatus.shouldSend() && exitStatus.shouldSend()) {
      snapshot.setTraceId(CorrelationAccess.instance().getTraceId());
      snapshot.setSpanId(CorrelationAccess.instance().getSpanId());
      if (isCaptureSnapshot()) {
        snapshot.setEntry(entryContext);
        snapshot.setExit(exitContext);
      }
      snapshot.setMessage(message);
      snapshot.setDuration(exitContext.getDuration());
      snapshot.addCaughtExceptions(caughtExceptions);
      shouldCommit = true;
    }
    if (entryStatus.shouldReportError()) {
      populateErrors(entryContext, snapshot, entryStatus, snapshot::setEntry);
      shouldCommit = true;
    }
    if (exitStatus.shouldReportError()) {
      populateErrors(exitContext, snapshot, exitStatus, snapshot::setExit);
      shouldCommit = true;
    }
    return shouldCommit;
  }

  private static void populateErrors(
      CapturedContext context,
      Snapshot snapshot,
      LogStatus status,
      Consumer<CapturedContext> contextSetter) {
    if (context.getCapturedThrowable() != null) {
      // report also uncaught exception
      contextSetter.accept(context);
    }
    snapshot.addEvaluationErrors(status.getErrors());
    if (status.getMessage() != null) {
      snapshot.setMessage(status.getMessage());
    } else if (!status.getErrors().isEmpty()) {
      snapshot.setMessage(status.getErrors().get(0).getMessage());
    }
  }

  private LogStatus convertStatus(CapturedContext.Status status) {
    if (status == CapturedContext.Status.EMPTY_STATUS) {
      return LogStatus.EMPTY_LOG_STATUS;
    }
    if (status == CapturedContext.Status.EMPTY_CAPTURING_STATUS) {
      return LogStatus.EMPTY_CAPTURING_LOG_STATUS;
    }
    return (LogStatus) status;
  }

  protected void commitSnapshot(Snapshot snapshot, DebuggerSink sink) {
    /*
     * Record stack trace having the caller of this method as 'top' frame.
     * For this it is necessary to discard:
     * - Thread.currentThread().getStackTrace()
     * - Snapshot.recordStackTrace()
     * - LogProbe.commitSnapshot
     * - ProbeDefinition.commit()
     * - DebuggerContext.commit() or DebuggerContext.evalAndCommit()
     */
    if (isCaptureSnapshot()) {
      snapshot.recordStackTrace(5);
      sink.addSnapshot(snapshot);
    } else {
      sink.addHighRateSnapshot(snapshot);
    }
  }

  @Override
  public void commit(CapturedContext lineContext, int line) {
    LogStatus status = (LogStatus) lineContext.getStatus(probeId.getEncodedId());
    if (status == null) {
      return;
    }
    DebuggerSink sink = DebuggerAgent.getSink();
    Snapshot snapshot = createSnapshot();
    boolean shouldCommit = false;
    if (status.shouldSend()) {
      snapshot.setTraceId(CorrelationAccess.instance().getTraceId());
      snapshot.setSpanId(CorrelationAccess.instance().getSpanId());
      snapshot.setMessage(status.getMessage());
      shouldCommit = true;
    }
    if (status.shouldReportError()) {
      snapshot.addEvaluationErrors(status.getErrors());
      shouldCommit = true;
    }
    if (shouldCommit) {
      incrementBudget();
      if (inBudget()) {
        if (isCaptureSnapshot()) {
          // freeze context just before commit because line probes have only one context
          Duration timeout =
              Duration.of(
                  Config.get().getDynamicInstrumentationCaptureTimeout(), ChronoUnit.MILLIS);
          lineContext.freeze(new TimeoutChecker(timeout));
          snapshot.addLine(lineContext, line);
        }
        commitSnapshot(snapshot, sink);
        return;
      }
    }
    sink.skipSnapshot(id, DebuggerContext.SkipCause.CONDITION);
  }

  @Override
  public boolean hasCondition() {
    return probeCondition != null;
  }

  protected String getDebugSessionId() {
    return getTagMap().get("session_id");
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new LogStatus(this);
  }

  public static class LogStatus extends CapturedContext.Status {
    public static final LogStatus EMPTY_LOG_STATUS =
        new LogStatus(ProbeImplementation.UNKNOWN, false);
    public static final LogStatus EMPTY_CAPTURING_LOG_STATUS =
        new LogStatus(ProbeImplementation.UNKNOWN, true);

    private boolean condition = true;
    private final DebugSessionStatus debugSessionStatus;
    private boolean hasLogTemplateErrors;
    private boolean hasConditionErrors;
    private boolean sampled = true;
    private boolean forceSampling;
    private String message;

    public LogStatus(ProbeImplementation probeImplementation) {
      super(probeImplementation);
      debugSessionStatus = debugSessionStatus();
    }

    private LogStatus(ProbeImplementation probeImplementation, boolean condition) {
      this(probeImplementation);
      this.condition = condition;
    }

    @Override
    public boolean shouldFreezeContext() {
      return sampled && probeImplementation.isCaptureSnapshot() && shouldSend();
    }

    @Override
    public boolean isCapturing() {
      return condition;
    }

    public boolean shouldSend() {
      DebugSessionStatus status = getDebugSessionStatus();
      // an ACTIVE status overrides the sampling as the sampling decision was made by the trigger
      // probe
      return status.isActive()
          || !status.isDefined() && sampled && condition && !hasConditionErrors;
    }

    public boolean shouldReportError() {
      return sampled && (hasConditionErrors || hasLogTemplateErrors);
    }

    public boolean getCondition() {
      return condition;
    }

    public DebugSessionStatus getDebugSessionStatus() {
      return debugSessionStatus;
    }

    private DebugSessionStatus debugSessionStatus() {
      if (probeImplementation instanceof LogProbe) {
        LogProbe definition = (LogProbe) probeImplementation;
        Map<String, String> sessions = getDebugSessions();
        String sessionId = definition.getDebugSessionId();
        if (sessionId == null) {
          return DebugSessionStatus.NONE;
        }
        return "1".equals(sessions.get(sessionId)) || "1".equals(sessions.get("*"))
            ? DebugSessionStatus.ACTIVE
            : DebugSessionStatus.DISABLED;
      }

      return DebugSessionStatus.NONE;
    }

    public void setCondition(boolean value) {
      this.condition = value;
    }

    public boolean hasConditionErrors() {
      return hasConditionErrors;
    }

    public void setConditionErrors(boolean value) {
      this.hasConditionErrors = value;
    }

    public boolean hasLogTemplateErrors() {
      return hasLogTemplateErrors;
    }

    public void setLogTemplateErrors(boolean value) {
      this.hasLogTemplateErrors = value;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public String getMessage() {
      return message;
    }

    public void setSampled(boolean sampled) {
      this.sampled = sampled;
    }

    public boolean isSampled() {
      return sampled;
    }

    public boolean isForceSampling() {
      return forceSampling;
    }

    public void setForceSampling(boolean forceSampling) {
      this.forceSampling = forceSampling;
    }

    @Override
    public String toString() {
      return "LogStatus{"
          + ", probeId="
          + probeImplementation.getId()
          + ", condition="
          + condition
          + ", debugSessionStatus="
          + debugSessionStatus
          + ", forceSampling="
          + forceSampling
          + ", hasConditionErrors="
          + hasConditionErrors
          + ", hasLogTemplateErrors="
          + hasLogTemplateErrors
          + ", message='"
          + message
          + '\''
          + ", sampled="
          + sampled
          + '}';
    }
  }

  private boolean inBudget() {
    AtomicInteger budgetLevel = getBudgetLevel();
    return budgetLevel == null
        || budgetLevel.get()
            <= (captureSnapshot ? CAPTURING_PROBE_BUDGET : NON_CAPTURING_PROBE_BUDGET);
  }

  private AtomicInteger getBudgetLevel() {
    TracerAPI tracer = AgentTracer.get();
    AgentSpan span = tracer != null ? tracer.activeSpan() : null;
    return getDebugSessionId() == null || span == null
        ? null
        : budget.computeIfAbsent(span.getLocalRootSpan().getTraceId(), id -> new AtomicInteger());
  }

  private void incrementBudget() {
    AtomicInteger budgetLevel = getBudgetLevel();
    if (budgetLevel != null) {
      budgetLevel.incrementAndGet();
      TracerAPI tracer = AgentTracer.get();
      AgentSpan span = tracer != null ? tracer.activeSpan() : null;
      if (span != null) {
        span.getLocalRootSpan().setTag(format("_dd.ld.probe_id.%s", id), budgetLevel.get());
      }
    }
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LogProbe that = (LogProbe) o;
    return Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && version == that.version
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt)
        && Objects.equals(template, that.template)
        && Objects.equals(segments, that.segments)
        && Objects.equals(captureSnapshot, that.captureSnapshot)
        && Objects.equals(probeCondition, that.probeCondition)
        && Objects.equals(capture, that.capture)
        && Objects.equals(sampling, that.sampling);
  }

  @Generated
  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            language,
            id,
            version,
            tagMap,
            where,
            evaluateAt,
            template,
            segments,
            captureSnapshot,
            probeCondition,
            capture,
            sampling);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "id='"
        + id
        + '\''
        + ", version="
        + version
        + ", tags="
        + Arrays.toString(tags)
        + ", where="
        + where
        + ", evaluateAt="
        + evaluateAt
        + ", template='"
        + template
        + '\''
        + ", segments="
        + segments
        + ", captureSnapshot="
        + captureSnapshot
        + ", when="
        + probeCondition
        + ", capture="
        + capture
        + ", sampling="
        + sampling
        + "} ";
  }

  public static LogProbe.Builder builder() {
    return new Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    private String template;
    private List<Segment> segments;
    private boolean captureSnapshot;
    private List<ValueScript> watches;
    private ProbeCondition probeCondition;
    private Capture capture;
    private Sampling sampling;
    private Consumer<Snapshot> snapshotProcessor;

    public Builder snapshotProcessor(Consumer<Snapshot> processor) {
      this.snapshotProcessor = processor;
      return this;
    }

    public Builder template(String template, List<Segment> segments) {
      this.template = template;
      this.segments = segments;
      return this;
    }

    public Builder captureSnapshot(boolean captureSnapshot) {
      this.captureSnapshot = captureSnapshot;
      return this;
    }

    public Builder capture(Capture capture) {
      this.capture = capture;
      return this;
    }

    public Builder sampling(Sampling sampling) {
      this.sampling = sampling;
      return this;
    }

    public Builder when(ProbeCondition probeCondition) {
      this.probeCondition = probeCondition;
      return this;
    }

    public Builder capture(
        int maxReferenceDepth, int maxCollectionSize, int maxLength, int maxFieldCount) {
      return capture(new Capture(maxReferenceDepth, maxCollectionSize, maxLength, maxFieldCount));
    }

    public Builder sampling(double rateLimit) {
      return sampling(new Sampling(rateLimit));
    }

    public LogProbe build() {
      return new LogProbe(this);
    }
  }

  @SuppressForbidden // String#split(String)
  private static Map<String, String> getDebugSessions() {
    HashMap<String, String> sessions = new HashMap<>();
    TracerAPI tracer = AgentTracer.get();
    if (tracer != null) {
      AgentSpan span = tracer.activeSpan();
      if (span instanceof DDSpan) {
        DDSpanContext context = (DDSpanContext) span.context();
        String debug = context.getPropagationTags().getDebugPropagation();
        if (debug != null) {
          String[] entries = debug.split(",");
          for (String entry : entries) {
            if (!entry.contains(":")) {
              sessions.put("*", entry);
            } else {
              String[] values = entry.split(":");
              sessions.put(values[0], values[1]);
            }
          }
        }
      }
    }
    return sessions;
  }
}

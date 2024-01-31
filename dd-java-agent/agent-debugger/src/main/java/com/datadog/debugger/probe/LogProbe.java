package com.datadog.debugger.probe;

import static com.datadog.debugger.probe.LogProbe.Capture.toLimits;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.agent.StringTemplateBuilder;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.CapturedContextInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.Snapshot;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores definition of a log probe */
public class LogProbe extends ProbeDefinition {
  private static final Logger LOGGER = LoggerFactory.getLogger(LogProbe.class);
  private static final Limits LIMITS = new Limits(1, 3, 8192, 5);
  private static final int LOG_MSG_LIMIT = 8192;

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
  public static final class Sampling {
    private final double snapshotsPerSecond;

    public Sampling(double snapshotsPerSecond) {
      this.snapshotsPerSecond = snapshotsPerSecond;
    }

    public double getSnapshotsPerSecond() {
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

  @Json(name = "when")
  private final ProbeCondition probeCondition;

  private final Capture capture;
  private final Sampling sampling;

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
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<ProbeId> probeIds) {
    return new CapturedContextInstrumentor(
            this,
            classLoader,
            classNode,
            methodNode,
            diagnostics,
            probeIds,
            isCaptureSnapshot(),
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
    CapturedContext.CapturedThrowable throwable = context.getThrowable();
    if (logStatus.hasConditionErrors() && throwable != null) {
      logStatus.addError(
          new EvaluationError(
              "uncaught exception", throwable.getType() + ": " + throwable.getMessage()));
    }
    if (hasCondition() && (logStatus.getCondition() || logStatus.hasConditionErrors())) {
      // sample if probe has condition and condition is true or has error
      sample(logStatus, methodLocation);
    }
    if (logStatus.isSampled() && logStatus.getCondition()) {
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
  }

  private void sample(LogStatus logStatus, MethodLocation methodLocation) {
    // sample only once and when we need to evaluate
    if (!MethodLocation.isSame(methodLocation, evaluateAt)) {
      return;
    }
    boolean sampled = ProbeRateLimiter.tryProbe(id);
    logStatus.setSampled(sampled);
    if (!sampled) {
      DebuggerAgent.getSink().skipSnapshot(id, DebuggerContext.SkipCause.RATE);
    }
  }

  private boolean evaluateCondition(CapturedContext capture, LogStatus status) {
    if (probeCondition == null) {
      return true;
    }
    long startTs = System.nanoTime();
    try {
      if (!probeCondition.execute(capture)) {
        return false;
      }
    } catch (EvaluationException ex) {
      status.addError(new EvaluationError(ex.getExpr(), ex.getMessage()));
      status.setConditionErrors(true);
      return false;
    } finally {
      LOGGER.debug(
          "ProbeCondition for probe[{}] evaluated in {}ns", id, (System.nanoTime() - startTs));
    }
    return true;
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions) {
    LogStatus entryStatus = convertStatus(entryContext.getStatus(probeId.getEncodedId()));
    LogStatus exitStatus = convertStatus(exitContext.getStatus(probeId.getEncodedId()));
    String message = null;
    String traceId = null;
    String spanId = null;
    switch (evaluateAt) {
      case ENTRY:
      case DEFAULT:
        message = entryStatus.getMessage();
        traceId = entryContext.getTraceId();
        spanId = entryContext.getSpanId();
        break;
      case EXIT:
        message = exitStatus.getMessage();
        traceId = exitContext.getTraceId();
        spanId = exitContext.getSpanId();
        break;
    }
    DebuggerSink sink = DebuggerAgent.getSink();
    boolean shouldCommit = false;
    int maxDepth = capture != null ? capture.maxReferenceDepth : -1;
    Snapshot snapshot = new Snapshot(Thread.currentThread(), this, maxDepth);
    if (entryStatus.shouldSend() && exitStatus.shouldSend()) {
      snapshot.setTraceId(traceId);
      snapshot.setSpanId(spanId);
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
      if (entryContext.getThrowable() != null) {
        // report also uncaught exception
        snapshot.setEntry(entryContext);
      }
      snapshot.addEvaluationErrors(entryStatus.getErrors());
      shouldCommit = true;
    }
    if (exitStatus.shouldReportError()) {
      if (exitContext.getThrowable() != null) {
        // report also uncaught exception
        snapshot.setExit(exitContext);
      }
      snapshot.addEvaluationErrors(exitStatus.getErrors());
      shouldCommit = true;
    }
    if (shouldCommit) {
      commitSnapshot(snapshot, sink);
    } else {
      sink.skipSnapshot(id, DebuggerContext.SkipCause.CONDITION);
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

  private void commitSnapshot(Snapshot snapshot, DebuggerSink sink) {
    /*
     * Record stack trace having the caller of this method as 'top' frame.
     * For this it is necessary to discard:
     * - Thread.currentThread().getStackTrace()
     * - Snapshot.recordStackTrace()
     * - LogProbe.commitSnapshot
     * - ProbeDefinition.commit()
     * - DebuggerContext.commit() or DebuggerContext.evalAndCommit()
     */
    snapshot.recordStackTrace(5);
    sink.addSnapshot(snapshot);
  }

  @Override
  public void commit(CapturedContext lineContext, int line) {
    LogStatus status = (LogStatus) lineContext.getStatus(probeId.getEncodedId());
    if (status == null) {
      return;
    }
    DebuggerSink sink = DebuggerAgent.getSink();
    int maxDepth = capture != null ? capture.maxReferenceDepth : -1;
    Snapshot snapshot = new Snapshot(Thread.currentThread(), this, maxDepth);
    boolean shouldCommit = false;
    if (status.shouldSend()) {
      snapshot.setTraceId(lineContext.getTraceId());
      snapshot.setSpanId(lineContext.getSpanId());
      if (isCaptureSnapshot()) {
        snapshot.addLine(lineContext, line);
      }
      snapshot.setMessage(status.getMessage());
      shouldCommit = true;
    }
    if (status.shouldReportError()) {
      snapshot.addEvaluationErrors(status.getErrors());
      shouldCommit = true;
    }
    if (shouldCommit) {
      // freeze context just before commit because line probes have only one context
      Duration timeout = Duration.of(Config.get().getDebuggerCaptureTimeout(), ChronoUnit.MILLIS);
      lineContext.freeze(new TimeoutChecker(timeout));
      commitSnapshot(snapshot, sink);
      return;
    }
    sink.skipSnapshot(id, DebuggerContext.SkipCause.CONDITION);
  }

  @Override
  public boolean hasCondition() {
    return probeCondition != null;
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new LogStatus(this);
  }

  public static class LogStatus extends CapturedContext.Status {
    private static final LogStatus EMPTY_LOG_STATUS =
        new LogStatus(ProbeImplementation.UNKNOWN, false);
    private static final LogStatus EMPTY_CAPTURING_LOG_STATUS =
        new LogStatus(ProbeImplementation.UNKNOWN, true);

    private boolean condition = true;
    private boolean hasLogTemplateErrors;
    private boolean hasConditionErrors;
    private boolean sampled = true;
    private String message;

    public LogStatus(ProbeImplementation probeImplementation) {
      super(probeImplementation);
    }

    private LogStatus(ProbeImplementation probeImplementation, boolean condition) {
      super(probeImplementation);
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
      return sampled && condition && !hasConditionErrors;
    }

    public boolean shouldReportError() {
      return hasConditionErrors || hasLogTemplateErrors;
    }

    public boolean getCondition() {
      return condition;
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
    return "LogProbe{"
        + "language='"
        + language
        + '\''
        + ", id='"
        + id
        + '\''
        + ", version="
        + version
        + ", tags="
        + Arrays.toString(tags)
        + ", tagMap="
        + tagMap
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
    private ProbeCondition probeCondition;
    private Capture capture;
    private Sampling sampling;

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
      return new LogProbe(
          language,
          probeId,
          tagStrs,
          where,
          evaluateAt,
          template,
          segments,
          captureSnapshot,
          probeCondition,
          capture,
          sampling);
    }
  }
}

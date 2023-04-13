package com.datadog.debugger.probe;

import static datadog.trace.bootstrap.debugger.util.TimeoutChecker.DEFAULT_TIME_OUT;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.agent.LogMessageTemplateBuilder;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.LogInstrumentor;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.io.IOException;
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

    public static Limits toLimits(Capture capture) {
      if (capture == null) {
        return null;
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
  public void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds) {
    new LogInstrumentor(this, classLoader, classNode, methodNode, diagnostics, probeIds)
        .instrument();
  }

  @Override
  public void evaluate(Snapshot.CapturedContext context, Snapshot.CapturedContext.Status status) {
    status.setCondition(evaluateCondition(context, status));
    Snapshot.CapturedThrowable throwable = context.getThrowable();
    if (status.hasConditionErrors() && throwable != null) {
      status.addError(
          new Snapshot.EvaluationError(
              "uncaught exception", throwable.getType() + ": " + throwable.getMessage()));
    }
    if (status.getCondition()) {
      LogMessageTemplateBuilder logMessageBuilder = new LogMessageTemplateBuilder(segments);
      status.setMessage(logMessageBuilder.evaluate(context, status));
    }
  }

  private boolean evaluateCondition(
      Snapshot.CapturedContext capture, Snapshot.CapturedContext.Status status) {
    if (probeCondition == null) {
      return true;
    }
    long startTs = System.nanoTime();
    try {
      if (!probeCondition.execute(capture)) {
        return false;
      }
    } catch (EvaluationException ex) {
      LOGGER.debug("Evaluation error: ", ex);
      status.addError(new Snapshot.EvaluationError(ex.getExpr(), ex.getMessage()));
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
      Snapshot.CapturedContext entryContext,
      Snapshot.CapturedContext exitContext,
      List<Snapshot.CapturedThrowable> caughtExceptions) {
    Snapshot.CapturedContext.Status entryStatus = entryContext.getStatus(id);
    Snapshot.CapturedContext.Status exitStatus = exitContext.getStatus(id);
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
    Snapshot snapshot = new Snapshot(Thread.currentThread(), this);
    if (entryStatus.shouldSend() && exitStatus.shouldSend()) {
      // only rate limit if a condition is defined
      if (probeCondition != null) {
        if (!ProbeRateLimiter.tryProbe(id)) {
          DebuggerContext.skipSnapshot(id, DebuggerContext.SkipCause.RATE);
          return;
        }
      }
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
      commitSnapshot(snapshot);
    } else {
      DebuggerContext.skipSnapshot(id, DebuggerContext.SkipCause.CONDITION);
    }
  }

  private void commitSnapshot(Snapshot snapshot) {
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
    DebuggerContext.addSnapshot(snapshot);
  }

  @Override
  public void commit(Snapshot.CapturedContext lineContext, int line) {
    Snapshot.CapturedContext.Status status = lineContext.getStatus(id);
    if (status == null) {
      return;
    }
    Snapshot snapshot = new Snapshot(Thread.currentThread(), this);
    boolean shouldCommit = false;
    if (status.shouldSend()) {
      // only rate limit if a condition is defined
      if (probeCondition != null) {
        if (!ProbeRateLimiter.tryProbe(id)) {
          DebuggerContext.skipSnapshot(id, DebuggerContext.SkipCause.RATE);
          return;
        }
      }
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
      lineContext.freeze(new TimeoutChecker(DEFAULT_TIME_OUT));
      commitSnapshot(snapshot);
      return;
    }
    DebuggerContext.skipSnapshot(id, DebuggerContext.SkipCause.CONDITION);
  }

  @Override
  public boolean hasCondition() {
    return probeCondition != null;
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

package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.LogInstrumentor;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Stores definition of a log probe */
public class LogProbe extends ProbeDefinition {

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
        true,
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
      boolean active,
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
        active,
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
      boolean active,
      Tag[] tags,
      Where where,
      MethodLocation evaluateAt,
      String template,
      List<Segment> segments,
      boolean captureSnapshot,
      ProbeCondition probeCondition,
      Capture capture,
      Sampling sampling) {
    super(language, probeId, active, tags, where, evaluateAt);
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
        active,
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

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LogProbe that = (LogProbe) o;
    return active == that.active
        && Objects.equals(language, that.language)
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
            active,
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
        + ", active="
        + active
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
          active,
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

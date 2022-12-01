package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.instrumentation.SnapshotInstrumentor;
import com.squareup.moshi.Json;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.Limits;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Stores definition of a snapshot probe */
public class SnapshotProbe extends ProbeDefinition {

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

  @Json(name = "when")
  private final ProbeCondition probeCondition;

  private final Capture capture;
  private final Sampling sampling;

  public SnapshotProbe(
      String language,
      String probeId,
      boolean active,
      String[] tagStrs,
      Where where,
      MethodLocation evaluateAt,
      ProbeCondition probeCondition,
      Capture capture,
      Sampling sampling) {
    super(language, probeId, active, tagStrs, where, evaluateAt);
    this.probeCondition = probeCondition;
    this.capture = capture;
    this.sampling = sampling != null ? sampling : new Sampling(1.0);
  }

  public SnapshotProbe() {
    this(
        LANGUAGE,
        UUID.randomUUID().toString(),
        true,
        null,
        new Where(),
        MethodLocation.DEFAULT,
        null,
        null,
        null);
  }

  public static Builder builder() {
    return new Builder();
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
      List<DiagnosticMessage> diagnostics) {
    new SnapshotInstrumentor(this, classLoader, classNode, methodNode, diagnostics).instrument();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    private ProbeCondition probeCondition;
    private Capture capture;
    private Sampling sampling;

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

    public SnapshotProbe build() {
      return new SnapshotProbe(
          language, probeId, active, tagStrs, where, evaluateAt, probeCondition, capture, sampling);
    }
  }

  @Generated
  @Override
  public String toString() {
    return "DebuggerProbe{"
        + "language='"
        + language
        + '\''
        + ", id='"
        + id
        + '\''
        + ", active="
        + active
        + ", where="
        + where
        + ", evaluateAt="
        + evaluateAt
        + ", script="
        + probeCondition
        + ", capture="
        + capture
        + ", sampling="
        + sampling
        + ", tags="
        + Arrays.toString(tags)
        + ", tagMap="
        + tagMap
        + ", additionalProbes="
        + additionalProbes
        + '}';
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SnapshotProbe that = (SnapshotProbe) o;
    return active == that.active
        && Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt)
        && Objects.equals(probeCondition, that.probeCondition)
        && Objects.equals(capture, that.capture)
        && Objects.equals(sampling, that.sampling)
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(additionalProbes, that.additionalProbes);
  }

  @Generated
  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            language,
            id,
            active,
            where,
            evaluateAt,
            probeCondition,
            capture,
            sampling,
            tagMap,
            additionalProbes);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }
}

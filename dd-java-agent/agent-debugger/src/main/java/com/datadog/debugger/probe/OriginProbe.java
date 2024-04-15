package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.instrumentation.SpanOriginInstrumentor;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class OriginProbe extends ProbeDefinition {
  private final String dupName;

  private final String dupSig;

  private final String rewriteName;

  private final String rewriteSig;

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public OriginProbe(
      String language,
      ProbeId probeId,
      String dupName,
      String dupSig,
      String rewriteName,
      String rewriteSig) {
    super(LANGUAGE, probeId, new String[0], null, MethodLocation.DEFAULT);
    this.dupName = dupName;
    this.dupSig = dupSig;
    this.rewriteName = rewriteName;
    this.rewriteSig = rewriteSig;
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<ProbeId> probeIds) {
    return new SpanOriginInstrumentor(this, methodInfo, diagnostics, probeIds).instrument();
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, version, tagMap, where, evaluateAt);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    OriginProbe that = (OriginProbe) o;
    return Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && version == that.version
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt);
  }

  @Generated
  @Override
  public String toString() {
    return "SpanProbe{"
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
        + "} ";
  }

  public static OriginProbe.Builder builder() {
    return new OriginProbe.Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    private String dupName;

    private String dupSig;

    private String rewriteName;

    private String rewriteSig;

    public OriginProbe build() {
      return new OriginProbe(LANGUAGE, probeId, dupName, dupSig, rewriteName, rewriteSig);
    }

    public Builder duplicate(String dupName, String dupSig) {
      this.dupName = dupName;
      this.dupSig = dupSig;
      return this;
    }

    public Builder rewrite(String rewriteName, String rewriteSig) {
      this.rewriteName = rewriteName;
      this.rewriteSig = rewriteSig;
      return this;
    }
  }
}

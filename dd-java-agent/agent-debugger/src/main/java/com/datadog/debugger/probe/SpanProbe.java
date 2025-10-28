package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.instrumentation.SpanInstrumenter;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class SpanProbe extends ProbeDefinition {
  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public SpanProbe() {
    this(LANGUAGE, null, null, null);
  }

  public SpanProbe(String language, ProbeId probeId, String[] tagStrs, Where where) {
    super(language, probeId, tagStrs, where, MethodLocation.DEFAULT);
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<Integer> probeIndices) {
    return new SpanInstrumenter(this, methodInfo, diagnostics, probeIndices).instrument();
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
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SpanProbe that = (SpanProbe) o;
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
        + "} ";
  }

  public static SpanProbe.Builder builder() {
    return new SpanProbe.Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    public SpanProbe build() {
      return new SpanProbe(LANGUAGE, probeId, tagStrs, where);
    }
  }
}

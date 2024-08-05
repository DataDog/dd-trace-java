package com.datadog.debugger.probe;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.CapturedContextInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebuggerProbe extends ProbeDefinition {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerProbe.class);

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public DebuggerProbe() {
    this(LANGUAGE, null, null);
  }

  public DebuggerProbe(String language, ProbeId probeId, Where where) {
    super(language, probeId, (Tag[]) null, where, MethodLocation.ENTRY);
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<ProbeId> probeIds) {
    return new CapturedContextInstrumentor(this, methodInfo, diagnostics, probeIds, false, null)
        .instrument();
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {
    decorateTags();
  }

  private void decorateTags() {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();

    AgentSpan agentSpan = tracerAPI.activeSpan().getLocalRootSpan();
    agentSpan.setTag("_dd.p.debug", "1");
    agentSpan.setTag("_dd.ld.probe_id", probeId.getId());

    DebuggerAgent.getSink().getProbeStatusSink().addEmitting(probeId);
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new CapturedContext.Status(this);
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DebuggerProbe that = (DebuggerProbe) o;
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
  public int hashCode() {
    int result = Objects.hash(language, id, version, where, evaluateAt);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "language='"
        + language
        + '\''
        + ", id='"
        + id
        + '\''
        + ", version="
        + version
        + ", where="
        + where
        + ", evaluateAt="
        + evaluateAt
        + "} ";
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    public DebuggerProbe build() {
      return new DebuggerProbe(language, probeId, where);
    }
  }
}

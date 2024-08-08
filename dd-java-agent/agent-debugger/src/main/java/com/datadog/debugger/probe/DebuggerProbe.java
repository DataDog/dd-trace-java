package com.datadog.debugger.probe;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.CapturedContextInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebuggerProbe extends ProbeDefinition {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerProbe.class);

  public enum TargetSpan {
    ACTIVE,
    ROOT
  }

  private final TargetSpan targetSpan;

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public DebuggerProbe() {
    this(LANGUAGE, null, null, null, MethodLocation.DEFAULT, TargetSpan.ACTIVE);
  }

  public DebuggerProbe(
      String language,
      ProbeId probeId,
      String[] tagStrs,
      Where where,
      MethodLocation methodLocation,
      TargetSpan targetSpan) {
    super(language, probeId, tagStrs, where, methodLocation);
    this.targetSpan = targetSpan;
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<ProbeId> probeIds) {
    return new CapturedContextInstrumentor(
            this, methodInfo, diagnostics, probeIds, false, Limits.DEFAULT)
        .instrument();
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {
    DebuggerStatus debuggerStatus = (DebuggerStatus) status;
    debuggerStatus.addTag("_dd.p.debug", "1");
    debuggerStatus.addTag("_dd.ld.probe_id", probeId.getEncodedId());
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions) {
    CapturedContext.Status status =
        evaluateAt == MethodLocation.EXIT
            ? exitContext.getStatus(probeId.getEncodedId())
            : entryContext.getStatus(probeId.getEncodedId());
    if (status == null) {
      return;
    }
    DebuggerStatus spanStatus = (DebuggerStatus) status;
    decorateTags(spanStatus);
    handleEvaluationErrors(spanStatus);
  }

  @Override
  public void commit(CapturedContext lineContext, int line) {
    CapturedContext.Status status = lineContext.getStatus(probeId.getEncodedId());
    if (status == null) {
      return;
    }
    DebuggerStatus spanStatus = (DebuggerStatus) status;
    decorateTags(spanStatus);
    handleEvaluationErrors(spanStatus);
  }

  private void decorateTags(DebuggerStatus status) {
    List<Pair<String, String>> tagsToDecorate = status.getTagsToDecorate();
    if (tagsToDecorate == null) {
      return;
    }
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan agentSpan = tracerAPI.activeSpan();
    if (agentSpan == null) {
      LOGGER.debug("Cannot find current active span");
      return;
    }
    if (targetSpan == TargetSpan.ROOT) {
      agentSpan = agentSpan.getLocalRootSpan();
      if (agentSpan == null) {
        LOGGER.debug("Cannot find root span");
        return;
      }
    }
    for (Pair<String, String> tag : tagsToDecorate) {
      agentSpan.setTag(tag.getLeft(), tag.getRight());
    }
    DebuggerAgent.getSink().getProbeStatusSink().addEmitting(probeId);
  }

  private void handleEvaluationErrors(DebuggerStatus status) {
    if (status.getErrors().isEmpty()) {
      return;
    }
    Snapshot snapshot = new Snapshot(Thread.currentThread(), this, -1);
    snapshot.addEvaluationErrors(status.getErrors());
    DebuggerAgent.getSink().addSnapshot(snapshot);
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new DebuggerStatus(this);
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, version, tagMap, where, evaluateAt, targetSpan);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
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
        && Objects.equals(evaluateAt, that.evaluateAt)
        && Objects.equals(targetSpan, that.targetSpan);
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
        + ", targetSpan="
        + targetSpan
        + "} ";
  }

  private static class DebuggerStatus extends CapturedContext.Status {
    private final List<Pair<String, String>> tagsToDecorate = new ArrayList<>();

    public DebuggerStatus(ProbeImplementation probeImplementation) {
      super(probeImplementation);
    }

    public void addTag(String tagName, String tagValue) {
      tagsToDecorate.add(Pair.of(tagName, tagValue));
    }

    public List<Pair<String, String>> getTagsToDecorate() {
      return tagsToDecorate;
    }

    @Override
    public boolean isCapturing() {
      return true;
    }
  }

  public static DebuggerProbe.Builder builder() {
    return new DebuggerProbe.Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<DebuggerProbe.Builder> {
    private TargetSpan targetSpan;

    public Builder targetSpan(TargetSpan targetSpan) {
      this.targetSpan = targetSpan;
      return this;
    }

    public DebuggerProbe build() {
      return new DebuggerProbe(LANGUAGE, probeId, tagStrs, where, evaluateAt, targetSpan);
    }
  }
}

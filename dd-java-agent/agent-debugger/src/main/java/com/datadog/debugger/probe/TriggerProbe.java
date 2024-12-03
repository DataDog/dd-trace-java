package com.datadog.debugger.probe;

import static java.lang.String.format;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.instrumentation.CapturedContextInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerProbe extends ProbeDefinition implements Sampled {
  private static final Logger LOGGER = LoggerFactory.getLogger(TriggerProbe.class);

  private ProbeCondition probeCondition;
  private Sampling sampling;
  private String sessionId;

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public TriggerProbe() {
    this(null, null, null, null, null);
  }

  public TriggerProbe(
      ProbeId probeId,
      String[] tagStrs,
      Where where,
      ProbeCondition probeCondition,
      Sampling sampling) {
    super("java", probeId, tagStrs, where, MethodLocation.ENTRY);
    this.probeCondition = probeCondition;
    this.sampling = sampling;
  }

  public TriggerProbe(ProbeId probeId, Where where) {
    this(probeId, null, where, null, null);
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<ProbeId> probeIds) {
    return new CapturedContextInstrumentor(this, methodInfo, diagnostics, probeIds, false, null)
        .instrument();
  }

  public Sampling getSampling() {
    return sampling;
  }

  public TriggerProbe setSampling(Sampling sampling) {
    this.sampling = sampling;
    return this;
  }

  public TriggerProbe setProbeCondition(ProbeCondition probeCondition) {
    this.probeCondition = probeCondition;
    return this;
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation location) {

    if (sampling == null || !sampling.inCoolDown()) {
      boolean sample = true;
      if (!hasCondition()) {
        sample = MethodLocation.isSame(location, evaluateAt) && ProbeRateLimiter.tryProbe(id);
      }
      boolean value = evaluateCondition(context);

      if (hasCondition() && value || !hasCondition() && sample) {
        decorateTags();
      }
    }
  }

  private boolean evaluateCondition(CapturedContext capture) {
    if (probeCondition == null) {
      return true;
    }
    long start = System.nanoTime();
    try {
      return !probeCondition.execute(capture);
    } catch (EvaluationException ex) {
      DebuggerAgent.getSink().getProbeStatusSink().addError(probeId, ex);
      return false;
    } finally {
      LOGGER.debug(
          "ProbeCondition for probe[{}] evaluated in {}ns", id, (System.nanoTime() - start));
    }
  }

  private void decorateTags() {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();

    AgentSpan agentSpan = tracerAPI.activeSpan().getLocalRootSpan();
    agentSpan.setTag(Tags.PROPAGATED_DEBUG, "1");
    agentSpan.setTag(format("_dd.ld.probe_id.%s", probeId.getId()), true);
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new CapturedContext.Status(this);
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
    TriggerProbe that = (TriggerProbe) o;
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

  @Override
  public String toString() {
    return format(
        "TriggerProbe{id='%s', where=%s, sampling=%s, probeCondition=%s}",
        id, where, sampling, probeCondition);
  }
}

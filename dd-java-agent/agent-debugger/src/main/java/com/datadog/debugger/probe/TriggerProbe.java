package com.datadog.debugger.probe;

import datadog.trace.util.HashingUtils;
import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.instrumentation.CapturedContextInstrumenter;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import datadog.trace.api.sampling.Sampler;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedContextProbe;
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

import static java.lang.String.format;

public class TriggerProbe extends ProbeDefinition implements Sampled, CapturedContextProbe {
  private static final Logger LOGGER = LoggerFactory.getLogger(TriggerProbe.class);

  private ProbeCondition probeCondition;
  private Sampling sampling;
  private String sessionId;
  private transient Sampler sampler;

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
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<Integer> probeIndices) {
    return new CapturedContextInstrumenter(
            this, methodInfo, diagnostics, probeIndices, false, false, null)
        .instrument();
  }

  public TriggerProbe setSessionId(String sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  public Sampling getSampling() {
    return sampling;
  }

  @Override
  public void initSamplers() {
    double rate = sampling != null ? sampling.getEventsPerSecond() : 1.0;
    sampler = ProbeRateLimiter.createSampler(rate);
  }

  @Override
  public boolean isCaptureSnapshot() {
    return false;
  }

  @Override
  public boolean hasCondition() {
    return probeCondition != null;
  }

  @Override
  public boolean isReadyToCapture() {
    return true;
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
      CapturedContext context,
      CapturedContext.Status status,
      MethodLocation location,
      boolean singleProbe) {

    Sampling sampling = getSampling();
    if (sampling == null || !sampling.inCoolDown()) {
      boolean sample = true;
      if (!hasCondition()) {
        sample =
            MethodLocation.isSame(location, evaluateAt) && ProbeRateLimiter.tryProbe(sampler, true);
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
      return probeCondition.execute(capture);
    } catch (Exception ex) {
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
    agentSpan.setTag(Tags.PROPAGATED_DEBUG, sessionId + ":1");
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
    int result = HashingUtils.hash(language, id, version, where, evaluateAt);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Override
  public String toString() {
    return String.format(
        "TriggerProbe{id='%s', sessionId='%s', evaluateAt=%s, language='%s', location=%s, probeCondition=%s, probeId=%s,"
            + " sampling=%s, tagMap=%s, tags=%s, version=%d, where=%s}",
        id,
        sessionId,
        evaluateAt,
        language,
        location,
        probeCondition,
        probeId,
        sampling,
        tagMap,
        Arrays.toString(tags),
        version,
        where);
  }
}

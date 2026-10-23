package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.SamplingMechanism.EXTERNAL_OVERRIDE;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.propagation.PropagationTags;
import org.junit.jupiter.api.Test;

/** A decision against an unparsed {@code ot} member records its consistency check for later. */
class DeferredOtelCheckTest {
  // rv >= th, so the member is only consistent with a sampled decision
  private static final String TRACESTATE = "dd=s:0;p:123456789abcdef0,ot=th:8;rv:c8b1e2d3f4a596";

  @Test
  void samePriorityStillRecordsANewCheck() {
    PropagationTags tags = PropagationTags.factory().fromHeaderValue(W3C, TRACESTATE);
    assertEquals(SAMPLER_DROP, tags.getSamplingPriority());

    tags.updateTraceSamplingPriority(SAMPLER_DROP, EXTERNAL_OVERRIDE);

    CharSequence otelTraceState = tags.samplingState().getOtelTraceState();
    assertFalse(otelTraceState.toString().contains("th:"), otelTraceState.toString());
  }

  @Test
  void repeatedDecisionKeepsTheState() {
    PropagationTags tags = PropagationTags.factory().fromHeaderValue(W3C, TRACESTATE);
    tags.updateTraceSamplingPriority(SAMPLER_DROP, EXTERNAL_OVERRIDE);
    PropagationTags.SamplingState afterFirst = tags.samplingState();

    tags.updateTraceSamplingPriority(SAMPLER_DROP, EXTERNAL_OVERRIDE);

    assertSame(afterFirst, tags.samplingState());
  }
}

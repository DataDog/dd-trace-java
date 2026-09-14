package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.propagation.PropagationTags.SamplingState;
import org.junit.jupiter.api.Test;

class OtelTraceStatePropagationTest {

  @Test
  void publishesProbabilityPriorityAndOtelStateTogether() {
    PropagationTags tags = PropagationTags.factory().empty();

    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_KEEP, AGENT_RATE, 1.0, true, 1L, false));
    SamplingState state = tags.samplingState();

    assertEquals(SAMPLER_KEEP, state.getSamplingPriority());
    assertEquals("-1", state.getDecisionMaker().toString());
    assertEquals("1", state.getKnuthSamplingRate().toString());
    assertTrue(state.getOtelTraceState().toString().matches("rv:[0-9a-f]{14};th:0"));
  }

  @Test
  void rejectedSamplingAttemptCannotReplaceProbabilityState() {
    PropagationTags tags = PropagationTags.factory().empty();
    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_KEEP, AGENT_RATE, 0.5, true, 1L, false));
    SamplingState established = tags.samplingState();

    assertFalse(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_DROP, AGENT_RATE, 0.1, false, 2L, false));

    assertEquals(established, tags.samplingState());
  }

  @Test
  void forceKeepRemovesLocallyGeneratedProbabilityState() {
    PropagationTags tags = PropagationTags.factory().empty();
    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_DROP, AGENT_RATE, 0.0, false, 1L, false));

    tags.forceKeep(MANUAL);

    assertEquals(USER_KEEP, tags.samplingState().getSamplingPriority());
    assertNull(tags.samplingState().getOtelTraceState());
  }

  @Test
  void limiterDemotionDoesNotFabricateState() {
    PropagationTags tags = PropagationTags.factory().empty();

    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_DROP, AGENT_RATE, 1.0, true, 1L, false));

    assertNull(tags.samplingState().getOtelTraceState());
  }
}

package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.EXTERNAL_OVERRIDE;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.core.propagation.PropagationTags.SamplingState;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OtelTraceStatePropagationTest {
  private static final String RV = "ef284ace7a91e1";
  private static final String TH = "e6666666666668";

  @Test
  void reusesEmptySamplingState() {
    PropagationTags.Factory factory = PropagationTags.factory();

    assertSame(factory.empty().samplingState(), factory.empty().samplingState());
  }

  @ParameterizedTest
  @MethodSource("inboundTracestates")
  void normalizesAndForwardsOnlyFirstManagedOtelMember(String header, String expected) {
    PropagationTags tags = PropagationTags.factory().fromHeaderValue(W3C, header);

    assertEquals(expected, tags.headerValue(W3C));
  }

  static Stream<Arguments> inboundTracestates() {
    return Stream.of(
        arguments("ot=rv:" + RV + ";th:" + TH, "ot=rv:" + RV + ";th:" + TH),
        arguments("ot=rv:" + RV, "ot=rv:" + RV),
        arguments("ot=th:" + TH, "ot=th:" + TH),
        arguments("ot=future:value", "ot=future:value"),
        arguments(
            "vendor=state,ot=rv:invalid;th:" + TH + ";future:value",
            "ot=future:value,vendor=state"),
        arguments("vendor=state,ot=rv:invalid;th:invalid", "vendor=state"),
        arguments("ot=rv:EF284ACE7A91E1;th:" + TH, null),
        arguments(
            "vendor=state,ot=rv:" + RV + ",ot=rv:1234567890abcd,other=state",
            "ot=rv:" + RV + ",vendor=state,other=state"),
        arguments("dd=s:1,dd=s:0,ot=rv:" + RV, "dd=s:1,ot=rv:" + RV));
  }

  @Test
  void rebuildsDuplicateDatadogMembers() {
    PropagationTags tags =
        PropagationTags.factory().fromHeaderValue(W3C, "dd=s:1,dd=s:0,ot=rv:" + RV);

    assertEquals("dd=s:1,ot=rv:" + RV, tags.getW3CTracestate(tags.samplingState()));
  }

  @Test
  void publishesProbabilityPriorityAndOtelStateTogether() {
    PropagationTags tags = PropagationTags.factory().empty();
    SamplingState before = tags.samplingState();

    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_KEEP, AGENT_RATE, 1.0, false, 1L, false));
    SamplingState after = tags.samplingState();

    assertEquals(SAMPLER_KEEP, after.getSamplingPriority());
    assertEquals("-1", after.getDecisionMaker().toString());
    assertEquals("1", after.getKnuthSamplingRate().toString());
    assertTrue(after.getOtelTraceState().toString().matches("rv:[0-9a-f]{14};th:0"));
    assertNull(tags.getW3CTracestate(before));
    String tracestate = tags.getW3CTracestate(after);
    assertEquals("ot=" + after.getOtelTraceState(), tracestate);
    assertSame(tracestate, tags.getW3CTracestate(after));
  }

  @Test
  void rejectedSamplingAttemptCannotReplaceProbabilityState() {
    PropagationTags tags = PropagationTags.factory().empty();
    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_KEEP, AGENT_RATE, 0.5, false, 1L, false));
    SamplingState established = tags.samplingState();

    assertFalse(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_DROP, AGENT_RATE, 0.1, false, 2L, false));

    assertEquals(established, tags.samplingState());
  }

  @Test
  void atomicPriorityUpdatePreservesLockedDecisionMaker() {
    PropagationTags tags =
        PropagationTags.factory().fromHeaderValue(DATADOG, "_dd.p.dm=934086a686-4");

    assertTrue(tags.tryUpdateTraceSamplingPriority(SAMPLER_KEEP, AGENT_RATE, false));

    SamplingState state = tags.samplingState();
    assertEquals(SAMPLER_KEEP, state.getSamplingPriority());
    assertEquals("934086a686-4", state.getDecisionMaker().toString());
  }

  @Test
  void atomicProbabilityUpdatePreservesLockedDecisionMaker() {
    PropagationTags tags =
        PropagationTags.factory().fromHeaderValue(DATADOG, "_dd.p.dm=934086a686-4");

    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_DROP, AGENT_RATE, 0.5, false, 1L, false));

    SamplingState state = tags.samplingState();
    assertEquals(SAMPLER_DROP, state.getSamplingPriority());
    assertEquals("934086a686-4", state.getDecisionMaker().toString());
    assertEquals("0.5", state.getKnuthSamplingRate().toString());
    assertTrue(state.getOtelTraceState().toString().matches("rv:[0-9a-f]{14};th:8"));
  }

  @Test
  void inheritedStateRetainsItsPositionRelativeToVendors() {
    PropagationTags tags =
        PropagationTags.factory()
            .fromHeaderValue(W3C, "vendor=state,ot=rv:ef284ace7a91e1;th:e6666666666668,dd=s:1");
    tags.updateTraceSamplingPriority(SAMPLER_KEEP, EXTERNAL_OVERRIDE);

    assertEquals(
        "dd=s:1;t.dm:-0,vendor=state,ot=rv:ef284ace7a91e1;th:e6666666666668",
        tags.headerValue(W3C));
  }

  @Test
  void preservesFinalUnchangedInheritedOtelMember() {
    PropagationTags tags =
        PropagationTags.factory().fromHeaderValue(W3C, "dd=s:1,first=value,sec=value,ot=rv:" + RV);

    assertEquals("dd=s:1,first=value,sec=value,ot=rv:" + RV, tags.headerValue(W3C));
  }

  @Test
  void compoundConflictRemovesThresholdAndRetainsRandomValue() {
    PropagationTags tags =
        PropagationTags.factory()
            .fromHeaderValue(W3C, "dd=s:1,ot=rv:00000000000001;th:8,vendor=state");

    tags.updateTraceSamplingPriority(SAMPLER_KEEP, EXTERNAL_OVERRIDE);

    assertEquals("dd=s:1;t.dm:-0,ot=rv:00000000000001,vendor=state", tags.headerValue(W3C));
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

  @Test
  void generatedManagedMembersDisplaceRightmostVendorAtMemberLimit() {
    StringBuilder original = new StringBuilder("v0=state");
    for (int i = 1; i < 31; i++) {
      original.append(",v").append(i).append("=state");
    }
    PropagationTags tags = PropagationTags.factory().fromHeaderValue(W3C, original.toString());

    assertTrue(
        tags.tryUpdateProbabilitySamplingDecision(SAMPLER_KEEP, AGENT_RATE, 0.5, false, 1L, false));

    String header = tags.headerValue(W3C);
    assertEquals(32, header.split(",").length);
    assertTrue(header.startsWith("dd=s:1;t.dm:-1;t.ksr:0.5,ot=rv:"));
    assertFalse(header.contains("v30=state"));
  }
}

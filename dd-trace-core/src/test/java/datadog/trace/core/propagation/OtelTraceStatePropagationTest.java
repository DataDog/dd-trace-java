package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.LOCAL_USER_RULE;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.api.sampling.SamplingMechanism.UNKNOWN;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OtelTraceStatePropagationTest {
  private static final long TRACE_ID = 1;
  private static final double SAMPLE_RATE_0_5 = 0.5;
  private static final String RV = "ef284ace7a91e1";
  private static final String TH = "e6666666666668";
  private static final String GENERATED_RV = "f0948a54d43b8e";
  private static final String THRESHOLD_0_5 = "8";
  private static final int W3C_MEMBER_VALUE_MAX_LENGTH = 256;
  private static final int W3C_TRACESTATE_MEMBER_LIMIT = 32;

  @ParameterizedTest
  @MethodSource("inboundTraceState")
  void normalizesAndForwardsFirstOtelMember(String header, String expected) {
    PropagationTags propagationTags = PropagationTags.factory().fromHeaderValue(W3C, header);

    assertEquals(expected, propagationTags.headerValue(W3C));
  }

  static Stream<Arguments> inboundTraceState() {
    // Covers valid, invalid, duplicate, and ordered ot list-members.
    return Stream.of(
        arguments("ot=rv:" + RV + ";th:" + TH, "ot=rv:" + RV + ";th:" + TH),
        arguments("ot=rv:" + RV, "ot=rv:" + RV),
        arguments("ot=th:" + TH, "ot=th:" + TH),
        arguments("ot=foo:bar", "ot=foo:bar"),
        arguments(
            "congo=state,ot=rv:invalid;th:" + TH + ";foo:bar",
            "ot=th:" + TH + ";foo:bar,congo=state"),
        arguments("congo=state,ot=rv:invalid;th:invalid", "congo=state"),
        arguments("ot=rv:EF284ACE7A91E1;th:" + TH, "ot=th:" + TH),
        arguments(
            "ot=rv:" + RV + ";rv:1234567890abcd;th:" + TH,
            "ot=rv:" + RV + ";rv:1234567890abcd;th:" + TH),
        arguments(
            "foo=bar,ot=rv:" + RV + ",ot=rv:1234567890abcd,other=state",
            "foo=bar,ot=rv:" + RV + ",other=state"),
        arguments("dd=s:1,dd=s:0,ot=rv:" + RV, "dd=s:1,ot=rv:" + RV));
  }

  @Test
  void preservesUnchangedInheritedMemberPosition() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(W3C, "dd=s:1,foo=bar,ot=rv:" + RV + ",something=else");

    assertEquals(
        "dd=s:1,foo=bar,ot=rv:" + RV + ",something=else", propagationTags.headerValue(W3C));
  }

  @Test
  void movesNormalizedMemberImmediatelyAfterDatadog() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(W3C, "dd=s:1,foo=bar,ot=rv:invalid;th:" + TH + ",something=else");

    assertEquals(
        "dd=s:1,ot=th:" + TH + ",foo=bar,something=else", propagationTags.headerValue(W3C));
  }

  @Test
  void preservesInheritedMemberWhenLocalProbabilitySamplingIsAttempted() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(
                W3C, "foo=bar,ot=rv:" + RV + ";th:" + TH + ";future:value,other=state");

    propagationTags.updateTraceSamplingPriority(USER_KEEP, LOCAL_USER_RULE);
    propagationTags.updateOtelTraceState(TRACE_ID, SAMPLE_RATE_0_5, true);

    assertEquals(
        "dd=s:2;t.dm:-3,foo=bar,ot=rv:" + RV + ";th:" + TH + ";future:value,other=state",
        propagationTags.headerValue(W3C));
  }

  @Test
  void manualKeepRetainsInheritedRandomnessAndUnknownFields() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(
                W3C, "foo=bar,ot=rv:" + RV + ";th:" + TH + ";future:value,other=state");

    propagationTags.forceKeep(MANUAL);

    assertEquals(
        "foo=bar,ot=rv:" + RV + ";th:" + TH + ";future:value,other=state",
        propagationTags.getW3CTracestate());
    assertEquals(
        "dd=s:2;t.dm:-4,ot=rv:" + RV + ";future:value,foo=bar,other=state",
        propagationTags.headerValue(W3C));
  }

  @Test
  void initialUnknownPriorityDoesNotOverrideInheritedProbabilityDecision() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(W3C, "foo=bar,ot=rv:" + RV + ";th:" + TH + ";future:value");

    propagationTags.updateTraceSamplingPriority(USER_KEEP, UNKNOWN);

    assertEquals(
        "dd=s:2,foo=bar,ot=rv:" + RV + ";th:" + TH + ";future:value",
        propagationTags.headerValue(W3C));
  }

  @Test
  void manualKeepRemovesLocallyGeneratedRandomness() {
    PropagationTags propagationTags = PropagationTags.factory().empty();
    propagationTags.updateTraceSamplingPriority(USER_KEEP, LOCAL_USER_RULE);
    propagationTags.updateOtelTraceState(TRACE_ID, SAMPLE_RATE_0_5, true);

    propagationTags.forceKeep(MANUAL);

    assertEquals("dd=s:2;t.dm:-4", propagationTags.headerValue(W3C));
  }

  @Test
  void sampledContextWithoutOtelStateDoesNotFabricateIt() {
    PropagationTags propagationTags =
        PropagationTags.factory().fromHeaderValue(W3C, "dd=s:1,foo=bar");

    assertEquals("dd=s:1,foo=bar", propagationTags.headerValue(W3C));
  }

  @Test
  void serializesSamplingStateFromOnePrioritySnapshot() {
    PropagationTags propagationTags =
        PropagationTags.factory().fromHeaderValue(W3C, "dd=s:2,ot=rv:" + RV + ";th:" + TH);

    assertEquals("dd=s:0,ot=rv:" + RV, propagationTags.headerValue(W3C, null, SAMPLER_DROP));
  }

  @Test
  void effectiveTracestateOnlyUpdatesOtelMember() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(W3C, "dd=s:1;t.dm:-3,vendor=state,ot=rv:" + RV + ";th:" + TH);

    assertEquals(
        "dd=s:1;t.dm:-3,ot=rv:" + RV + ",vendor=state",
        propagationTags.getW3CTracestate(SAMPLER_DROP));
  }

  @Test
  void effectiveTracestateAddsOnlyLocallyGeneratedOtelMember() {
    PropagationTags propagationTags = PropagationTags.factory().empty();
    propagationTags.updateTraceSamplingPriority(USER_KEEP, LOCAL_USER_RULE);
    propagationTags.updateOtelTraceState(TRACE_ID, SAMPLE_RATE_0_5, true);

    assertEquals(
        "ot=rv:" + GENERATED_RV + ";th:" + THRESHOLD_0_5,
        propagationTags.getW3CTracestate(USER_KEEP));
  }

  @Test
  void updatingW3cTraceStateCapturesOtelStateForMixedHeaderExtraction() {
    PropagationTags propagationTags =
        PropagationTags.factory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-3");

    propagationTags.updateW3CTracestate("foo=bar,ot=rv:" + RV + ";th:" + TH + ",other=state");

    assertEquals(
        "dd=t.dm:-3,foo=bar,ot=rv:" + RV + ";th:" + TH + ",other=state",
        propagationTags.headerValue(W3C));
  }

  @Test
  void preservesInheritedOtelFieldsAtMemberSizeLimit() {
    // W3C limits an individual tracestate member value to 256 characters.
    String unknownField = "x:" + repeat("v", W3C_MEMBER_VALUE_MAX_LENGTH - 2);
    PropagationTags propagationTags =
        PropagationTags.factory().fromHeaderValue(W3C, "ot=" + unknownField);

    assertEquals("ot=" + unknownField, propagationTags.headerValue(W3C));

    propagationTags.updateTraceSamplingPriority(USER_KEEP, LOCAL_USER_RULE);
    propagationTags.updateOtelTraceState(TRACE_ID, SAMPLE_RATE_0_5, true);

    String header = propagationTags.headerValue(W3C);
    assertTrue(header.contains("ot=" + unknownField));
    assertFalse(header.contains("ot=rv:"));
  }

  @Test
  void generatedOtelMemberHonorsMemberCountLimit() {
    StringBuilder original = new StringBuilder("v0=state");
    for (int i = 1; i < W3C_TRACESTATE_MEMBER_LIMIT - 1; i++) {
      original.append(",v").append(i).append("=state");
    }
    PropagationTags propagationTags =
        PropagationTags.factory().fromHeaderValue(W3C, original.toString());

    propagationTags.updateTraceSamplingPriority(USER_KEEP, LOCAL_USER_RULE);
    propagationTags.updateOtelTraceState(TRACE_ID, SAMPLE_RATE_0_5, true);

    String header = propagationTags.headerValue(W3C);
    assertEquals(W3C_TRACESTATE_MEMBER_LIMIT, header.split(",").length);
    assertTrue(
        header.startsWith("dd=s:2;t.dm:-3,ot=rv:" + GENERATED_RV + ";th:" + THRESHOLD_0_5 + ","));
    assertFalse(header.contains("v30=state"));
  }

  private static String repeat(String value, int count) {
    StringBuilder repeated = new StringBuilder(value.length() * count);
    for (int i = 0; i < count; i++) {
      repeated.append(value);
    }
    return repeated.toString();
  }
}

package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.common.sampling.DeterministicSampler;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OtelTraceStateTest {

  @ParameterizedTest
  @MethodSource("goldenSamplingVectors")
  void createsGoldenSamplingState(double sampleRate, boolean sampled, String expectedTraceState) {
    OtelTraceState state =
        OtelTraceState.updateProbability(
            null, 1, sampleRate, sampled, sampled ? SAMPLER_KEEP : SAMPLER_DROP);

    assertEquals(expectedTraceState, state.getValue());
  }

  static Stream<Arguments> goldenSamplingVectors() {
    return Stream.of(
        arguments(0.01, false, "rv:f0948a54d43b8e;th:fd70a3d70a3d7"),
        arguments(0.1, true, "rv:f0948a54d43b8e;th:e6666666666668"),
        arguments(0.2, true, "rv:f0948a54d43b8e;th:ccccccccccccd"),
        arguments(0.5, true, "rv:f0948a54d43b8e;th:8"),
        arguments(0.99, true, "rv:f0948a54d43b8e;th:028f5c28f5c29"));
  }

  @Test
  void omitsStateAtRateZero() {
    assertNull(OtelTraceState.updateProbability(null, 1, 0, false, SAMPLER_DROP));
  }

  @Test
  void clampsTinyPositiveRateThreshold() {
    OtelTraceState state =
        OtelTraceState.updateProbability(null, 1, Double.MIN_VALUE, false, SAMPLER_DROP);

    assertEquals("rv:f0948a54d43b8e;th:ffffffffffffff", state.getValue());
  }

  @Test
  void correctsKeepAtPrecisionBoundary() {
    OtelTraceState state =
        OtelTraceState.updateProbability(null, 0x03A93EE8B1999F00L, 0.1, true, SAMPLER_KEEP);

    assertEquals("rv:e6666666666668;th:e6666666666668", state.getValue());
  }

  @Test
  void correctsDropAtPrecisionBoundary() {
    OtelTraceState state =
        OtelTraceState.updateProbability(null, 5401449561355763072L, 0.05, false, SAMPLER_DROP);

    assertEquals("rv:f333333333332f;th:f333333333333", state.getValue());
  }

  @Test
  void limiterRejectionRemovesLocallyGeneratedProbability() {
    OtelTraceState state =
        OtelTraceState.updateProbability(
            OtelTraceState.parse("foo:bar", 2), 1, 0.5, true, SAMPLER_KEEP);

    state = OtelTraceState.updateProbability(state, 1, 0.5, true, SAMPLER_DROP);

    assertEquals("foo:bar", state.getValue());
    assertEquals(0, state.getInheritedPosition());
  }

  @Test
  void limiterRejectionRetainsInheritedRandomness() {
    OtelTraceState state =
        OtelTraceState.parse("rv:ef284ace7a91e1;th:8;foo:bar", 2);

    state = OtelTraceState.updateProbability(state, 1, 0.5, true, SAMPLER_DROP);

    assertEquals("rv:ef284ace7a91e1;foo:bar", state.getValue());
    assertEquals(0, state.getInheritedPosition());
  }

  @Test
  void nonProbabilityDecisionRetainsOnlyInheritedRandomnessAndUnknownFields() {
    OtelTraceState state = OtelTraceState.parse("th:e6666666666668;foo:bar;rv:ef284ace7a91e1", 2);

    state = state.removeForNonProbabilityDecision();

    assertEquals("rv:ef284ace7a91e1;foo:bar", state.getValue());
    assertEquals(0, state.getInheritedPosition());
  }

  @Test
  void deterministicSamplerRetainsDoublePrecisionRate() {
    double rate = 0.123456789012345;

    assertEquals(rate, new DeterministicSampler.TraceSampler(rate).getSampleRate());
  }
}

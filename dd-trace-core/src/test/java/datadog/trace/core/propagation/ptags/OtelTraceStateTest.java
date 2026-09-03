package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OtelTraceStateTest {

  // A stable trace ID covers sampling decisions at representative rates.
  private static final long TRACE_ID = 1;
  private static final long OTEL_SPEC_WORKED_EXAMPLE_TRACE_ID = 0xfff972474538efffL;
  private static final String LOCAL_RANDOM_VALUE = "f0948a54d43b8e";
  private static final double SAMPLE_RATE_0_01 = 0.01;
  private static final String THRESHOLD_0_01 = "fd70a3d70a3d7";
  private static final double SAMPLE_RATE_0_1 = 0.1;
  private static final String THRESHOLD_0_1 = "e6666666666668";
  private static final double SAMPLE_RATE_0_2 = 0.2;
  private static final String THRESHOLD_0_2 = "ccccccccccccd";
  private static final double SAMPLE_RATE_0_5 = 0.5;
  private static final String THRESHOLD_0_5 = "8";
  private static final double SAMPLE_RATE_0_99 = 0.99;
  private static final String THRESHOLD_0_99 = "028f5c28f5c29";
  private static final double SAMPLE_RATE_0_05 = 0.05;

  // These IDs straddle thresholds that would otherwise lose precision when rounded.
  private static final long KEEP_PRECISION_BOUNDARY_TRACE_ID = 0x03A93EE8B1999F00L;
  private static final String KEEP_PRECISION_BOUNDARY_VALUE = "e6666666666668";
  private static final long DROP_PRECISION_BOUNDARY_TRACE_ID = 5401449561355763072L;
  private static final String DROP_PRECISION_BOUNDARY_RANDOM_VALUE = "f333333333332f";
  private static final String DROP_PRECISION_BOUNDARY_THRESHOLD = "f333333333333";
  private static final String INHERITED_RANDOM_VALUE = "ef284ace7a91e1";
  private static final String UNKNOWN_FIELD = "foo:bar";
  private static final int INHERITED_POSITION = 2;
  private static final String TINY_POSITIVE_THRESHOLD = "ffffffffffffff";

  @ParameterizedTest
  @MethodSource("goldenSamplingVectors")
  void createsGoldenSamplingState(double sampleRate, boolean sampled, String expectedTraceState) {
    OtelTraceState state =
        OtelTraceState.updateProbability(
            null, TRACE_ID, sampleRate, sampled, sampled ? SAMPLER_KEEP : SAMPLER_DROP);

    assertEquals(expectedTraceState, state.getValue());
  }

  static Stream<Arguments> goldenSamplingVectors() {
    return Stream.of(
        arguments(SAMPLE_RATE_0_01, false, traceState(LOCAL_RANDOM_VALUE, THRESHOLD_0_01)),
        arguments(SAMPLE_RATE_0_1, true, traceState(LOCAL_RANDOM_VALUE, THRESHOLD_0_1)),
        arguments(SAMPLE_RATE_0_2, true, traceState(LOCAL_RANDOM_VALUE, THRESHOLD_0_2)),
        arguments(SAMPLE_RATE_0_5, true, traceState(LOCAL_RANDOM_VALUE, THRESHOLD_0_5)),
        arguments(SAMPLE_RATE_0_99, true, traceState(LOCAL_RANDOM_VALUE, THRESHOLD_0_99)));
  }

  @Test
  void matchesOtelSpecWorkedExample() {
    OtelTraceState state =
        OtelTraceState.updateProbability(
            null, OTEL_SPEC_WORKED_EXAMPLE_TRACE_ID, SAMPLE_RATE_0_1, true, SAMPLER_KEEP);

    assertEquals(traceState(INHERITED_RANDOM_VALUE, THRESHOLD_0_1), state.getValue());
  }

  @Test
  void emitsMaxThresholdAtRateZero() {
    OtelTraceState state = OtelTraceState.updateProbability(null, TRACE_ID, 0, false, SAMPLER_DROP);

    assertEquals(traceState(LOCAL_RANDOM_VALUE, TINY_POSITIVE_THRESHOLD), state.getValue());
  }

  @Test
  void clampsTinyPositiveRateThreshold() {
    OtelTraceState state =
        OtelTraceState.updateProbability(null, TRACE_ID, Double.MIN_VALUE, false, SAMPLER_DROP);

    assertEquals(traceState(LOCAL_RANDOM_VALUE, TINY_POSITIVE_THRESHOLD), state.getValue());
  }

  @Test
  void correctsKeepAtPrecisionBoundary() {
    OtelTraceState state =
        OtelTraceState.updateProbability(
            null, KEEP_PRECISION_BOUNDARY_TRACE_ID, SAMPLE_RATE_0_1, true, SAMPLER_KEEP);

    assertEquals(
        traceState(KEEP_PRECISION_BOUNDARY_VALUE, KEEP_PRECISION_BOUNDARY_VALUE), state.getValue());
  }

  @Test
  void correctsDropAtPrecisionBoundary() {
    OtelTraceState state =
        OtelTraceState.updateProbability(
            null, DROP_PRECISION_BOUNDARY_TRACE_ID, SAMPLE_RATE_0_05, false, SAMPLER_DROP);

    assertEquals(
        traceState(DROP_PRECISION_BOUNDARY_RANDOM_VALUE, DROP_PRECISION_BOUNDARY_THRESHOLD),
        state.getValue());
  }

  @Test
  void limiterRejectionRetainsLocallyGeneratedRandomness() {
    OtelTraceState state =
        OtelTraceState.updateProbability(
            OtelTraceState.parse(UNKNOWN_FIELD, INHERITED_POSITION),
            TRACE_ID,
            SAMPLE_RATE_0_5,
            true,
            SAMPLER_KEEP);

    state = OtelTraceState.updateProbability(state, TRACE_ID, SAMPLE_RATE_0_5, true, SAMPLER_DROP);

    assertEquals("rv:" + LOCAL_RANDOM_VALUE + ";" + UNKNOWN_FIELD, state.getValue());
    assertEquals(0, state.getInheritedPosition());
  }

  @Test
  void limiterRejectionRetainsInheritedRandomness() {
    OtelTraceState state =
        OtelTraceState.parse(
            traceState(INHERITED_RANDOM_VALUE, THRESHOLD_0_5) + ";" + UNKNOWN_FIELD,
            INHERITED_POSITION);

    state = OtelTraceState.updateProbability(state, TRACE_ID, SAMPLE_RATE_0_5, true, SAMPLER_DROP);

    assertEquals("rv:" + INHERITED_RANDOM_VALUE + ";" + UNKNOWN_FIELD, state.getValue());
    assertEquals(0, state.getInheritedPosition());
  }

  @Test
  void nonProbabilityDecisionRetainsOnlyInheritedRandomnessAndUnknownFields() {
    OtelTraceState state =
        OtelTraceState.parse(
            "th:" + THRESHOLD_0_1 + ";" + UNKNOWN_FIELD + ";rv:" + INHERITED_RANDOM_VALUE,
            INHERITED_POSITION);

    state = state.removeForNonProbabilityDecision();

    assertEquals("rv:" + INHERITED_RANDOM_VALUE + ";" + UNKNOWN_FIELD, state.getValue());
    assertEquals(0, state.getInheritedPosition());
  }

  private static String traceState(String randomValue, String threshold) {
    return "rv:" + randomValue + ";th:" + threshold;
  }
}

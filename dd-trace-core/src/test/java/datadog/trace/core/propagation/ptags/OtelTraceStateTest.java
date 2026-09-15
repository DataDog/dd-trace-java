package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OtelTraceStateTest {
  private static final long DROP_PRECISION_BOUNDARY_TRACE_ID = 5401449561355763072L;
  private static final double DROP_PRECISION_BOUNDARY_RATE = 0.05;
  private static final String DROP_PRECISION_BOUNDARY_RANDOM_VALUE = "f333333333332f";
  private static final String DROP_PRECISION_BOUNDARY_THRESHOLD = "f333333333333";

  @Test
  void convertsDatadogProbabilityDecision() {
    OtelTraceState state = OtelTraceState.fromProbabilityDecision(0xfff972474538efffL, 0.1, true);

    assertFalse(state.isMaterialized());
    assertEquals("rv:ef284ace7a91e1;th:e6666666666668", state.toString());
    assertTrue(state.isMaterialized());
    assertTrue(state.isConsistentWith(true));
  }

  @Test
  void serializesThresholds() {
    assertThreshold(0.01, "fd70a3d70a3d7");
    assertThreshold(0.1, "e6666666666668");
    assertThreshold(0.2, "ccccccccccccd");
    assertThreshold(0.5, "8");
    assertThreshold(0.99, "028f5c28f5c29");
    assertThreshold(1.0, "0");
  }

  @Test
  void rateZeroUsesLargestWireThresholdAndRemainsDropConsistent() {
    OtelTraceState state = OtelTraceState.fromProbabilityDecision(0L, 0.0, false);

    assertEquals("rv:fffffffffffffe;th:ffffffffffffff", state.toString());
    assertTrue(state.isConsistentWith(false));
  }

  @Test
  void correctsOnlySerializedRandomValueAtKeepBoundary() {
    OtelTraceState state = OtelTraceState.fromProbabilityDecision(0x03a93ee8b1999f00L, 0.1, true);

    assertEquals("rv:e6666666666668;th:e6666666666668", state.toString());
    assertTrue(state.isConsistentWith(true));
  }

  @Test
  void correctsOnlySerializedRandomValueAtDropBoundary() {
    OtelTraceState state =
        OtelTraceState.fromProbabilityDecision(
            DROP_PRECISION_BOUNDARY_TRACE_ID, DROP_PRECISION_BOUNDARY_RATE, false);

    assertEquals(
        "rv:" + DROP_PRECISION_BOUNDARY_RANDOM_VALUE + ";th:" + DROP_PRECISION_BOUNDARY_THRESHOLD,
        state.toString());
    assertTrue(state.isConsistentWith(false));
  }

  @Test
  void removesLocalRandomnessForNonProbabilityDecision() {
    OtelTraceState state = OtelTraceState.fromProbabilityDecision(1L, 1.0, true);

    assertNull(state.forNonProbabilityDecision());
  }

  @Test
  void retainsInheritedRandomnessAndUnknownFieldsWithoutThreshold() {
    OtelTraceState state = OtelTraceState.parse("rv:0123456789abcd;th:8;x:value", 0);

    OtelTraceState transformed = state.forNonProbabilityDecision();

    assertEquals("rv:0123456789abcd;x:value", transformed.toString());
    assertTrue(transformed.isConsistentWith(false));
  }

  private static void assertThreshold(double rate, String expectedThreshold) {
    OtelTraceState state = OtelTraceState.fromProbabilityDecision(1L, rate, rate > 0.0);
    String value = state.toString();
    assertEquals(expectedThreshold, value.substring(value.indexOf(";th:") + 4));
  }
}

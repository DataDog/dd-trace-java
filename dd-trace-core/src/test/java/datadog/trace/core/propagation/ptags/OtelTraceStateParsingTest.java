package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.util.SubSequence;
import org.junit.jupiter.api.Test;

class OtelTraceStateParsingTest {
  private static final String VALUE = "rv:0123456789abcd";
  private static final int ORIGINAL_MEMBER_CONTRIBUTION_SIZE = 21;

  @Test
  void ignoresAbsentValues() {
    assertNull(OtelTraceState.parse(null, ORIGINAL_MEMBER_CONTRIBUTION_SIZE));
    assertNull(OtelTraceState.parse("", ORIGINAL_MEMBER_CONTRIBUTION_SIZE));
  }

  @Test
  void retainsValueAndMemberSize() {
    SubSequence value = SubSequence.of(VALUE, 0, VALUE.length());
    OtelTraceState state = OtelTraceState.parse(value, ORIGINAL_MEMBER_CONTRIBUTION_SIZE);

    assertNotNull(state);
    assertFalse(state.isMaterialized());
    assertEquals(VALUE.length(), state.length());
    assertEquals(ORIGINAL_MEMBER_CONTRIBUTION_SIZE, state.getOriginalSize());
  }

  @Test
  void retainsValidThresholdWithoutRandomValue() {
    OtelTraceState state = OtelTraceState.parse("th:8", 0);

    assertEquals("th:8", state.toString());
  }

  @Test
  void removesMalformedThresholdAndRetainsValidRandomValue() {
    OtelTraceState state = OtelTraceState.parse("rv:0123456789abcd;th:not-hex;x:value", 0);

    assertEquals("rv:0123456789abcd;x:value", state.toString());
  }

  @Test
  void malformedRandomValueRemovesManagedPairButRetainsUnknownFields() {
    OtelTraceState state = OtelTraceState.parse("rv:invalid;th:8;x:value", 0);

    assertEquals("x:value", state.toString());
  }

  @Test
  void malformedFirstRandomValuePreventsRecoveryFromLaterValues() {
    OtelTraceState state =
        OtelTraceState.parse("rv:invalid;rv:0123456789abcd;rv:ffffffffffffff;th:8;th:4", 0);

    assertNull(state);
  }

  @Test
  void keepsFirstValidManagedFields() {
    OtelTraceState state = OtelTraceState.parse("rv:0123456789abcd;rv:ffffffffffffff;th:8;th:4", 0);

    assertEquals("rv:0123456789abcd;th:8", state.toString());
  }

  @Test
  void rejectsUppercaseAndOverlongManagedFields() {
    OtelTraceState state = OtelTraceState.parse("rv:0123456789ABCd;th:123456789abcdef;x:value", 0);

    assertEquals("x:value", state.toString());
  }
}

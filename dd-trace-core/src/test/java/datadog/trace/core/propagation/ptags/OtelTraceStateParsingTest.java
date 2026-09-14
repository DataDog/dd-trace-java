package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.util.SubSequence;
import org.junit.jupiter.api.Test;

class OtelTraceStateParsingTest {
  private static final String VALUE = "rv:0123456789abcd";
  private static final int INHERITED_POSITION = 2;
  private static final int ORIGINAL_MEMBER_CONTRIBUTION_SIZE = 21;

  @Test
  void ignoresAbsentValues() {
    assertNull(OtelTraceState.parse(null, INHERITED_POSITION, ORIGINAL_MEMBER_CONTRIBUTION_SIZE));
    assertNull(OtelTraceState.parse("", INHERITED_POSITION, ORIGINAL_MEMBER_CONTRIBUTION_SIZE));
  }

  @Test
  void retainsValueAndMemberMetadata() {
    SubSequence value = SubSequence.of(VALUE, 0, VALUE.length());
    OtelTraceState state =
        OtelTraceState.parse(value, INHERITED_POSITION, ORIGINAL_MEMBER_CONTRIBUTION_SIZE);

    assertNotNull(state);
    assertSame(value, state.getValue());
    assertFalse(state.isMaterialized());
    assertEquals(VALUE.length(), state.length());
    assertEquals(INHERITED_POSITION, state.getOriginalPosition());
    assertEquals(ORIGINAL_MEMBER_CONTRIBUTION_SIZE, state.getOriginalSize());
  }

  @Test
  void extractsOriginalMemberPosition() {
    OtelTraceState state =
        W3CPTagsCodec.extractOtelTraceState("first=value,dd=s:1,dd=s:0,ot=" + VALUE);

    assertNotNull(state);
    assertEquals(3, state.getOriginalPosition());
  }

  @Test
  void retainsValidThresholdWithoutRandomValue() {
    OtelTraceState state = OtelTraceState.parse("th:8", 0, 0);

    assertEquals("th:8", state.getValue().toString());
  }

  @Test
  void removesMalformedThresholdAndRetainsValidRandomValue() {
    OtelTraceState state = OtelTraceState.parse("rv:0123456789abcd;th:not-hex;x:value", 0, 0);

    assertEquals("rv:0123456789abcd;x:value", state.getValue().toString());
  }

  @Test
  void malformedRandomValueRemovesManagedPairButRetainsUnknownFields() {
    OtelTraceState state = OtelTraceState.parse("rv:invalid;th:8;x:value", 0, 0);

    assertEquals("x:value", state.getValue().toString());
  }

  @Test
  void malformedFirstRandomValuePreventsRecoveryFromLaterValues() {
    OtelTraceState state =
        OtelTraceState.parse("rv:invalid;rv:0123456789abcd;rv:ffffffffffffff;th:8;th:4", 0, 0);

    assertNull(state);
  }

  @Test
  void keepsFirstValidManagedFields() {
    OtelTraceState state =
        OtelTraceState.parse("rv:0123456789abcd;rv:ffffffffffffff;th:8;th:4", 0, 0);

    assertEquals("rv:0123456789abcd;th:8", state.getValue().toString());
  }

  @Test
  void rejectsUppercaseAndOverlongManagedFields() {
    OtelTraceState state =
        OtelTraceState.parse("rv:0123456789ABCd;th:123456789abcdef;x:value", 0, 0);

    assertEquals("x:value", state.getValue().toString());
  }
}

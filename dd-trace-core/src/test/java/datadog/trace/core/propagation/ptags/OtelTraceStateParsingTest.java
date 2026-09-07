package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    OtelTraceState state =
        OtelTraceState.parse(VALUE, INHERITED_POSITION, ORIGINAL_MEMBER_CONTRIBUTION_SIZE);

    assertNotNull(state);
    assertEquals(VALUE, state.getValue());
    assertEquals(VALUE.length(), state.length());
    assertEquals(INHERITED_POSITION, state.getInheritedPosition());
    assertEquals(ORIGINAL_MEMBER_CONTRIBUTION_SIZE, state.getOriginalMemberContributionSize());
  }
}

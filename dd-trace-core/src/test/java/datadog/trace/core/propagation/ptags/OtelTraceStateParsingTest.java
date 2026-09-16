package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

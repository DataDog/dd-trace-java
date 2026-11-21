package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * {@link W3CPTagsCodec#validateMemberValue}'s one-pass check of plain values matches the full state
 * machine, {@link W3CPTagsCodec#validateMemberValueSlowly}, on every input.
 */
class W3CMemberValueTest {

  private static void assertSame(String tracestate, int start) {
    assertEquals(
        W3CPTagsCodec.validateMemberValueSlowly(tracestate, start),
        W3CPTagsCodec.validateMemberValue(tracestate, start),
        () -> "'" + tracestate + "' from " + start);
  }

  @TableTest({
    "scenario                | tracestate                  | start",
    "plain value at end      | 'dd=s:1;p:00f067aa0ba902b7' | 3    ",
    "plain value then member | 'dd=s:1;t.dm:-4,ot=th:0'    | 3    ",
    "empty value             | 'dd=,ot=th:0'               | 3    ",
    "start past end          | 'dd='                       | 3    ",
    "space inside value      | 'vendor=a b,dd=s:1'         | 7    ",
    "trailing spaces         | 'vendor=ab  ,dd=s:1'        | 7    ",
    "only spaces             | 'vendor=   ,dd=s:1'         | 7    ",
    "tab then more           | 'vendor=a\tb,dd=s:1'        | 7    ",
    "trailing tab            | 'vendor=ab\t,dd=s:1'        | 7    ",
    "equals in value         | 'vendor=a=b,dd=s:1'         | 7    ",
    "tilde in value          | 'dd=t.f:w00t~~'             | 3    ",
    "non ascii in value      | 'vendor=é,dd=s:1'           | 7    ",
    "control char in value   | 'vendor=a\u0001b'           | 7    "
  })
  void matchesTheStateMachine(String tracestate, int start) {
    assertSame(tracestate, start);
  }

  @Test
  void matchesAroundTheLengthLimit() {
    for (int length = 250; length <= 262; length++) {
      StringBuilder value = new StringBuilder("v=");
      for (int i = 0; i < length; i++) {
        value.append((char) ('a' + i % 26));
      }
      String atEnd = value.toString();
      assertSame(atEnd, 2);
      assertSame(atEnd + ",dd=s:1", 2);
      assertSame(atEnd + "  ,dd=s:1", 2);
      assertSame(atEnd + "\t,dd=s:1", 2);
    }
  }

  @Test
  void matchesOnRandomValues() {
    char[] alphabet = {'a', 'z', '0', '9', ':', ';', '-', '_', '~', '.', ' ', '\t', ',', '=', 'é'};
    Random random = new Random(42);
    for (int run = 0; run < 20_000; run++) {
      int length = random.nextInt(8) == 0 ? 250 + random.nextInt(12) : random.nextInt(24);
      StringBuilder tracestate = new StringBuilder("k=");
      for (int i = 0; i < length; i++) {
        // mostly plain chars, so the one-pass check is exercised as well as the fallback
        tracestate.append(
            random.nextInt(4) == 0
                ? alphabet[random.nextInt(alphabet.length)]
                : (char) ('a' + random.nextInt(26)));
      }
      assertSame(tracestate.toString(), 2);
    }
  }
}

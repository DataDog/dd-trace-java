package datadog.trace.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Functions.GuardedBase64Decode;
import datadog.trace.util.AdaptiveLatch;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class GuardedBase64DecodeTest {

  private static final byte[] VALID =
      Base64.getEncoder().encode("x-datadog-trace-id".getBytes(UTF_8));
  private static final byte[] INVALID = "not-valid-base64!@#".getBytes(UTF_8);

  private static byte[] bytes(String s) {
    return s.getBytes(UTF_8);
  }

  private static GuardedBase64Decode engaged() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertNull(decode.tryApply(INVALID));
    assertTrue(decode.isEngaged());
    return decode;
  }

  @Test
  void decodesValidInput() {
    assertEquals("x-datadog-trace-id", new GuardedBase64Decode().tryApply(VALID));
  }

  @Test
  void invalidInputYieldsNullAndEngages() {
    GuardedBase64Decode decode = new GuardedBase64Decode();

    assertNull(decode.tryApply(INVALID));
    assertTrue(decode.isEngaged());
  }

  @Test
  void invalidInputStillYieldsNullWhileEngaged() {
    GuardedBase64Decode decode = engaged();

    assertNull(decode.tryApply(INVALID));
  }

  @Test
  void validInputStillDecodesWhileEngaged() {
    GuardedBase64Decode decode = engaged();

    assertEquals("x-datadog-trace-id", decode.tryApply(VALID));
  }

  @TableTest({
    "scenario        | input  | expected",
    "empty           | ''     | ''      ",
    "two-char unit   | YQ     | a       ",
    "three-char unit | YWI    | ab      ",
    "padded          | 'YQ==' | a       "
  })
  void unpaddedAndEmptyInputIsNotTurnedAwayWhileEngaged(String input, String expected) {
    // Base64.getDecoder() accepts input without padding, so the pre-check must not reject it
    GuardedBase64Decode decode = engaged();

    assertEquals(expected, decode.tryApply(bytes(input)));
  }

  @Test
  void disengagesAfterEnoughValidInput() {
    GuardedBase64Decode decode = engaged();

    for (int i = 0; i < AdaptiveLatch.DEFAULT_CLOSE_AFTER; i++) {
      decode.tryApply(VALID);
    }

    assertFalse(decode.isEngaged());
  }

  @Test
  void otherFailuresPropagate() {
    assertThrows(NullPointerException.class, () -> new GuardedBase64Decode().tryApply(null));
  }

  @TableTest({
    "scenario                     | input             | knownToFail",
    "empty                        | ''                | false      ",
    "two-char unit                | YQ                | false      ",
    "three-char unit              | YWI               | false      ",
    "full unit                    | YWJj              | false      ",
    "padded                       | 'YQ=='            | false      ",
    "misplaced padding is lenient | 'Y=Q='            | false      ",
    "one-char unit                | Y                 | true       ",
    "one-char final unit          | YWJjZ             | true       ",
    "space                        | 'YW Jj'           | true       ",
    "punctuation                  | not-valid-base64! | true       ",
    "url-safe alphabet            | 'YQ-_'            | true       "
  })
  void preCheckRejectsOnlyWhatTheDecoderWould(String input, boolean knownToFail) {
    assertEquals(knownToFail, GuardedBase64Decode.isDefinitelyNotBase64(bytes(input)));
    if (knownToFail) {
      // the pre-check is only allowed to turn away input the decoder really rejects
      assertThrows(IllegalArgumentException.class, () -> Base64.getDecoder().decode(input));
    }
  }
}

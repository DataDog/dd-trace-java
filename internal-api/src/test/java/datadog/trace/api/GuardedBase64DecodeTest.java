package datadog.trace.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.api.Functions.GuardedBase64Decode;
import java.util.Base64;
import java.util.Random;
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

  /** What {@link Base64#getDecoder()} makes of {@code src}: the decoded string, or null. */
  private static String jdk(byte[] src) {
    try {
      return new String(Base64.getDecoder().decode(src), UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static void assertAgreesWithTheJdk(byte[] src) {
    String expected;
    try {
      expected = jdk(src);
    } catch (RuntimeException e) {
      // the guard relies on the JDK decoder failing only with IllegalArgumentException
      throw new AssertionError("JDK decoder threw " + e + " for " + new String(src, UTF_8), e);
    }
    String actual = GuardedBase64Decode.decodeOrNull(src);
    if (expected == null ? actual != null : !expected.equals(actual)) {
      fail(
          "for \""
              + new String(src, UTF_8)
              + "\": JDK "
              + (expected == null ? "rejects" : "gives \"" + expected + "\"")
              + ", decodeOrNull "
              + (actual == null ? "rejects" : "gives \"" + actual + "\""));
    }
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
  void whileEngagedItStillDecodesValidInputAndRejectsInvalidInput() {
    GuardedBase64Decode decode = engaged();

    assertEquals("x-datadog-trace-id", decode.tryApply(VALID));
    assertNull(decode.tryApply(INVALID));
  }

  @Test
  void disengagesAfterEnoughValidInput() {
    GuardedBase64Decode decode = engaged();

    for (int i = 0; i < decode.closeAfter(); i++) {
      decode.tryApply(VALID);
    }

    assertFalse(decode.isEngaged());
  }

  @Test
  void otherFailuresPropagate() {
    assertThrows(NullPointerException.class, () -> new GuardedBase64Decode().tryApply(null));
  }

  @TableTest({
    "scenario                  | input      | expected",
    "empty                     | ''         | ''      ",
    "two-char unit, unpadded   | YQ         | a       ",
    "three-char unit, unpadded | YWI        | ab      ",
    "full unit                 | YWJj       | abc     ",
    "two-char unit, padded     | 'YQ=='     | a       ",
    "three-char unit, padded   | 'YWI='     | ab      ",
    "non-zero trailing bits    | 'YR=='     | a       ",
    "one char                  | Y          |         ",
    "one-char final unit       | YWJjZ      |         ",
    "dangling char, padded     | 'Y='       |         ",
    "lone padding              | '='        |         ",
    "padding starts a unit     | 'YWJj='    |         ",
    "two-char unit, one pad    | 'YQ='      |         ",
    "two-char unit, pad, char  | 'YQ=Y'     |         ",
    "char after padding        | 'YQ==Y'    |         ",
    "unit after padding        | 'YQ==YQ==' |         ",
    "space                     | 'YW Jj'    |         ",
    "url-safe alphabet         | 'YQ-_'     |         "
  })
  void decodeOrNullFollowsTheJdkRules(String input, String expected) {
    assertEquals(expected, GuardedBase64Decode.decodeOrNull(bytes(input)));
    assertAgreesWithTheJdk(bytes(input));
  }

  @Test
  void decodeOrNullAgreesWithTheJdkOnEveryShortInputOverAReducedAlphabet() {
    // every placement of padding, of alphabet characters with low and high bits set, and of an
    // illegal character, up to two full units
    byte[] symbols = bytes("AQ/=!");
    for (int length = 0; length <= 8; length++) {
      byte[] src = new byte[length];
      int[] digits = new int[length];
      while (true) {
        for (int i = 0; i < length; i++) {
          src[i] = symbols[digits[i]];
        }
        assertAgreesWithTheJdk(src.clone());
        int i = 0;
        while (i < length && ++digits[i] == symbols.length) {
          digits[i++] = 0;
        }
        if (i == length) {
          break;
        }
      }
    }
  }

  @Test
  void decodeOrNullAgreesWithTheJdkOnRandomEncodingsAndTheirMutations() {
    Random random = new Random(12672);
    Base64.Encoder[] encoders = {Base64.getEncoder(), Base64.getEncoder().withoutPadding()};
    byte[] mutations = bytes("=!-_ \nA/");
    for (int n = 0; n < 20_000; n++) {
      byte[] data = new byte[random.nextInt(48)];
      random.nextBytes(data);
      byte[] encoded = encoders[n & 1].encode(data);
      assertAgreesWithTheJdk(encoded);

      if (encoded.length > 0) {
        byte[] mutated = encoded.clone();
        mutated[random.nextInt(mutated.length)] = mutations[random.nextInt(mutations.length)];
        assertAgreesWithTheJdk(mutated);

        byte[] truncated = new byte[random.nextInt(encoded.length)];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        assertAgreesWithTheJdk(truncated);
      }

      byte[] noise = new byte[random.nextInt(16)];
      random.nextBytes(noise);
      assertAgreesWithTheJdk(noise);
    }
  }
}

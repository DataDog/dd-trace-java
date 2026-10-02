package datadog.trace.api.internal.util;

import static datadog.trace.api.internal.util.LongStringUtils.parseUnsignedLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.DDTraceApiTableTestConverters;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(DDTraceApiTableTestConverters.class)
class LongStringUtilsTest {
  private static final long SENTINEL = 42L;

  @TableTest({
    "scenario                    | input                  | expected           ",
    "zero                        | '0'                    | 0                  ",
    "one                         | '1'                    | 1                  ",
    "leading zeros               | '00001'                | 1                  ",
    "18 digits                   | '999999999999999999'   | 999999999999999999 ",
    "19 digits                   | '1000000000000000000'  | 1000000000000000000",
    "long max                    | '9223372036854775807'  | Long.MAX_VALUE     ",
    "long max plus one           | '9223372036854775808'  | Long.MIN_VALUE     ",
    "unsigned max                | '18446744073709551615' | -1                 ",
    "20 digits with leading zero | '00000000000000000001' | 1                  "
  })
  @ParameterizedTest(name = "parse valid decimal [{index}]")
  void parseValidDecimal(String input, long expected) {
    assertEquals(expected, parseUnsignedLong(input, 0, input.length(), SENTINEL));
    assertEquals(expected, parseUnsignedLong(input));
  }

  @ParameterizedTest(name = "non-throwing parse returns sentinel for invalid input [{index}]")
  @ValueSource(
      strings = {
        "",
        "-1",
        "+1",
        " 1",
        "1 ",
        "12a",
        "0x1a",
        "١", // ARABIC-INDIC DIGIT ONE
        "18446744073709551616", // unsigned max + 1
        "18446744073709551620", // first 19 digits already too large
        "99999999999999999999",
        "184467440737095516150", // 21 digits
        "000000000000000000001" // 21 digits, even with leading zeros
      })
  void nonThrowingParseReturnsSentinelForInvalidInput(String input) {
    assertEquals(SENTINEL, parseUnsignedLong(input, 0, input.length(), SENTINEL));
  }

  @Test
  void nonThrowingParseReturnsSentinelForNull() {
    assertEquals(SENTINEL, parseUnsignedLong(null, 0, 0, SENTINEL));
  }

  @TableTest({
    "scenario          | input   | start | len | expected",
    "middle            | 'x123y' | 1     | 3   | 123     ",
    "prefix            | '123y'  | 0     | 3   | 123     ",
    "negative start    | '123'   | -1    | 2   | 42      ",
    "past the end      | '123'   | 1     | 3   | 42      ",
    "zero length       | '123'   | 0     | 0   | 42      ",
    "includes bad char | 'x123y' | 1     | 4   | 42      "
  })
  @ParameterizedTest(name = "non-throwing parse of a range [{index}]")
  void nonThrowingParseOfARange(String input, int start, int len, long expected) {
    assertEquals(expected, parseUnsignedLong(input, start, len, SENTINEL));
  }

  @TableTest({
    "scenario        | input | expected",
    "leading plus    | '+1'  | 1       ",
    "non-ASCII digit | '١'   | 1       "
  })
  @ParameterizedTest(name = "throwing parse keeps accepting what Long.parseLong accepts [{index}]")
  void throwingParseKeepsAcceptingWhatLongParseLongAccepts(String input, long expected) {
    assertEquals(expected, parseUnsignedLong(input));
  }

  @ParameterizedTest(name = "throwing parse rejects invalid input [{index}]")
  @NullSource
  @ValueSource(strings = {"", "-1", "12a", "18446744073709551616", "184467440737095516150"})
  void throwingParseRejectsInvalidInput(String input) {
    assertThrows(NumberFormatException.class, () -> parseUnsignedLong(input));
  }

  @Test
  void matchesJdkForRandomUnsignedLongs() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < 10_000; i++) {
      long value = random.nextLong();
      String s = Long.toUnsignedString(value);
      assertEquals(value, parseUnsignedLong(s, 0, s.length(), SENTINEL), s);
    }
  }

  @Test
  void matchesJdkForRandomDigitStrings() {
    // Lengths around the 18 / 19 / 20 digit boundaries, where overflow handling changes. Longer
    // input is always rejected, even with leading zeros, matching parseUnsignedLong(String)
    ThreadLocalRandom random = ThreadLocalRandom.current();
    char[] digits = new char[20];
    for (int i = 0; i < 10_000; i++) {
      int len = 17 + random.nextInt(4);
      for (int j = 0; j < len; j++) {
        digits[j] = (char) ('0' + random.nextInt(10));
      }
      String s = new String(digits, 0, len);
      boolean valid;
      long expected = 0;
      try {
        expected = Long.parseUnsignedLong(s);
        valid = true;
      } catch (NumberFormatException e) {
        valid = false;
      }
      if (valid) {
        assertEquals(expected, parseUnsignedLong(s, 0, len, SENTINEL), s);
      } else {
        assertEquals(SENTINEL, parseUnsignedLong(s, 0, len, SENTINEL), s);
      }
    }
  }
}

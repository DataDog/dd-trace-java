package datadog.trace.api.internal.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * The range-based {@code CharSequence} parsers give the same result, or a {@code
 * NumberFormatException}, as their {@code String} counterparts on the same chars.
 */
class LongStringUtilsRangeTest {

  private static final String[] EDGES = {
    "",
    "0",
    "+",
    "-",
    "+0",
    "-0",
    "00",
    "007",
    "-1",
    "+1",
    "++1",
    "1+",
    " 1",
    "1 ",
    "a",
    "12a4",
    "٣", // ARABIC-INDIC DIGIT THREE, a digit to Character.digit
    "1٠2",
    "2147483647",
    "2147483648",
    "-2147483648",
    "-2147483649",
    "+2147483647",
    "9223372036854775807",
    "9223372036854775808",
    "-9223372036854775808",
    "-9223372036854775809",
    "+9223372036854775807",
    "999999999999999999",
    "1000000000000000000",
    "18446744073709551615",
    "18446744073709551616",
    "28446744073709551615",
    "00000000000000000001",
    "000000000000000000001",
    "+8446744073709551615",
    "1844674407370955161a",
    "184467440737095516150",
  };

  /** The outcome of a parse: its value, or "NFE". */
  private static String outcome(Callable<?> parse) {
    try {
      return String.valueOf(parse.call());
    } catch (NumberFormatException e) {
      return "NFE";
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void assertSameOutcomes(String s) {
    // embedded in a non-String CharSequence, with chars around it
    StringBuilder padded = new StringBuilder("x9").append(s).append("7y");
    int start = 2;
    int end = 2 + s.length();
    assertEquals(
        outcome(() -> Long.parseLong(s)),
        outcome(() -> LongStringUtils.parseLong(padded, start, end)),
        () -> "parseLong '" + s + "'");
    assertEquals(
        outcome(() -> Integer.parseInt(s)),
        outcome(() -> LongStringUtils.parseInt(padded, start, end)),
        () -> "parseInt '" + s + "'");
    assertEquals(
        outcome(() -> LongStringUtils.parseUnsignedLong(s)),
        outcome(() -> LongStringUtils.parseUnsignedLong(padded, start, end)),
        () -> "parseUnsignedLong '" + s + "'");
  }

  @Test
  void matchesTheStringParsersOnEdgeCases() {
    for (String edge : EDGES) {
      assertSameOutcomes(edge);
    }
  }

  @Test
  void matchesTheStringParsersOnRandomInput() {
    char[] alphabet = {'0', '1', '5', '9', '+', '-', 'a', ' ', '٣'};
    Random random = new Random(42);
    for (int run = 0; run < 50_000; run++) {
      int length = random.nextInt(22);
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < length; i++) {
        // mostly digits, so values near every length boundary come up
        s.append(
            random.nextInt(6) == 0
                ? alphabet[random.nextInt(alphabet.length)]
                : (char) ('0' + random.nextInt(10)));
      }
      assertSameOutcomes(s.toString());
    }
  }

  @Test
  void matchesOnRandomNumbersOfEveryMagnitude() {
    Random random = new Random(7);
    for (int run = 0; run < 20_000; run++) {
      long value = random.nextLong() >>> random.nextInt(64);
      assertSameOutcomes(Long.toUnsignedString(value));
      assertSameOutcomes(Long.toString(value));
      assertSameOutcomes(Long.toString(-value));
      assertSameOutcomes(Integer.toString((int) value));
    }
  }

  @Test
  void rejectsNullAndEmptyRanges() {
    assertThrows(NumberFormatException.class, () -> LongStringUtils.parseLong(null, 0, 0));
    assertThrows(NumberFormatException.class, () -> LongStringUtils.parseInt(null, 0, 0));
    assertThrows(NumberFormatException.class, () -> LongStringUtils.parseUnsignedLong(null, 0, 0));
    assertThrows(NumberFormatException.class, () -> LongStringUtils.parseLong("123", 1, 1));
    assertThrows(NumberFormatException.class, () -> LongStringUtils.parseInt("123", 2, 1));
  }
}

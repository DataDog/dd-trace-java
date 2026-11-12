package datadog.trace.api.internal.util;

import static datadog.trace.api.internal.util.LongStringUtils.parseUnsignedLongHex;
import static datadog.trace.api.internal.util.LongStringUtils.parseUnsignedLongHexOrZero;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class LongStringUtilsTest {

  /**
   * The non-throwing parse agrees with {@link LongStringUtils#parseUnsignedLongHex} wherever that
   * succeeds, and yields zero wherever it throws.
   */
  @TableTest({
    "scenario                | hex                    | start | length | lowerCaseOnly",
    "null input              |                        | 0     | 1      | false        ",
    "empty input             | ''                     | 0     | 0      | false        ",
    "negative start          | '00'                   | -1    | 1      | false        ",
    "past the end            | '00'                   | 1     | 2      | false        ",
    "single zero             | '0'                    | 0     | 1      | false        ",
    "one                     | '1'                    | 0     | 1      | false        ",
    "max                     | 'ffffffffffffffff'     | 0     | 16     | true         ",
    "max upper-case allowed  | 'FFFFFFFFFFFFFFFF'     | 0     | 16     | false        ",
    "upper-case rejected     | 'FFFFFFFFFFFFFFFF'     | 0     | 16     | true         ",
    "leading zeros beyond 16 | '0000ffffffffffffffff' | 0     | 20     | false        ",
    "17 significant digits   | '10000000000000000'    | 0     | 17     | false        ",
    "illegal character       | 'ffffffffffffffzf'     | 0     | 16     | false        ",
    "minus sign              | '-1'                   | 0     | 2      | false        ",
    "sub-range               | 'xx2ayy'               | 2     | 2      | true         "
  })
  void agreesWithTheThrowingParse(String hex, int start, int length, boolean lowerCaseOnly) {
    long expected;
    try {
      expected = parseUnsignedLongHex(hex, start, length, lowerCaseOnly);
    } catch (NumberFormatException e) {
      expected = 0;
    }

    assertEquals(expected, parseUnsignedLongHexOrZero(hex, start, length, lowerCaseOnly));
  }

  /** Significant digits are counted within the range, not from the start of the string. */
  @Test
  void rejectsMoreThanSixteenSignificantDigitsAtAnOffset() {
    assertEquals(0, parseUnsignedLongHexOrZero("x10000000000000000", 1, 17, false));
    assertEquals(0x1L, parseUnsignedLongHexOrZero("x00000000000000001", 1, 17, false));
  }
}

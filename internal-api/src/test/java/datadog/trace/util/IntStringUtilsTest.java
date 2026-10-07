package datadog.trace.util;

import static datadog.trace.util.IntStringUtils.parseNonNegativeInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class IntStringUtilsTest {

  @TableTest({
    "scenario         | input       | expected  ",
    "zero             | 0           | 0         ",
    "port             | 1521        | 1521      ",
    "leading zeros    | 007         | 7         ",
    "int max          | 2147483647  | 2147483647",
    "int max plus one | 2147483648  | -1        ",
    "far overflow     | 99999999999 | -1        ",
    "empty            | ''          | -1        ",
    "minus sign       | -1          | -1        ",
    "plus sign        | +1          | -1        ",
    "letters          | abc         | -1        ",
    "trailing garbage | 12a         | -1        ",
    "whitespace       | ' 1'        | -1        ",
    "non-ASCII digit  | '\u0661'    | -1        "
  })
  void parseNonNegativeIntWholeSequence(String input, int expected) {
    assertEquals(expected, parseNonNegativeInt(input));
  }

  @TableTest({
    "scenario            | input        | start | len | expected",
    "middle of string    | host:1521/db | 5     | 4   | 1521    ",
    "to end              | host:1521    | 5     | 4   | 1521    ",
    "empty range         | host:/db     | 5     | 0   | -1      ",
    "negative length     | host:1521    | 6     | -1  | -1      ",
    "negative start      | 1521         | -1    | 4   | -1      ",
    "past end            | 1521         | 1     | 4   | -1      ",
    "start past length   | 1521         | 5     | 1   | -1      ",
    "range spans garbage | host:1521/db | 4     | 5   | -1      "
  })
  void parseNonNegativeIntRange(String input, int start, int len, int expected) {
    assertEquals(expected, parseNonNegativeInt(input, start, len));
  }

  @Test
  void parseNonNegativeIntNull() {
    assertEquals(-1, parseNonNegativeInt(null));
    assertEquals(-1, parseNonNegativeInt(null, 0, 0));
  }
}

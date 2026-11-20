package datadog.trace.util;

import static datadog.trace.util.Numbers.parseNumber;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class NumbersTest {

  @TableTest({
    "scenario               | input                | expected            ",
    "zero                   | 0                    | 0                   ",
    "positive integer       | 42                   | 42                  ",
    "negative integer       | -100                 | -100                ",
    "integer with plus sign | +999                 | 999                 ",
    "large valid long       | 9223372036854775807  | 9223372036854775807 ",
    "large negative long    | -9223372036854775808 | -9223372036854775808"
  })
  void parseNumberWithValidIntegers(String input, long expected) {
    Number result = parseNumber(input);

    assertEquals(expected, result);
    assertInstanceOf(Long.class, result);
  }

  @TableTest({
    "scenario                 | input      | expected  ",
    "simple decimal           | 3.14       | 3.14      ",
    "negative decimal         | -0.5       | -0.5      ",
    "decimal with plus sign   | +99.99     | 99.99     ",
    "zero decimal             | 0.0        | 0.0       ",
    "decimal with many digits | 123.456789 | 123.456789",
    "decimal starts with dot  | .5         | 0.5       ",
    "decimal ends with dot    | 5.         | 5.0       "
  })
  void parseNumberWithValidDecimals(String input, double expected) {
    Number result = parseNumber(input);

    assertEquals(expected, (double) result, 0.0);
    assertInstanceOf(Double.class, result);
  }

  @TableTest({
    "scenario                          | input   | expected",
    "scientific notation lowercase     | 1e10    | 1.0e10  ",
    "scientific notation uppercase     | 1E10    | 1.0E10  ",
    "scientific with decimal           | 1.5e10  | 1.5e10  ",
    "scientific negative exponent      | 3.5E-7  | 3.5E-7  ",
    "scientific with positive exp sign | -2.5e+3 | -2.5e+3 ",
    "scientific integer base           | 5e3     | 5000.0  "
  })
  void parseNumberWithScientificNotation(String input, double expected) {
    Number result = parseNumber(input);

    assertEquals(expected, (double) result, 0.0);
    assertInstanceOf(Double.class, result);
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("parseNumberWithWhitespaceArguments")
  void parseNumberWithWhitespace(String description, String input, Object expected) {
    Number result = parseNumber(input);

    assertEquals(expected, result);
  }

  private static Stream<Arguments> parseNumberWithWhitespaceArguments() {
    return Stream.of(
        arguments("leading whitespace", " 42", 42L),
        arguments("trailing whitespace", "42 ", 42L),
        arguments("both whitespace", " 42 ", 42L),
        arguments("tab and newline", "\t100\n", 100L),
        arguments("multiple spaces decimal", "  -3.14  ", -3.14d));
  }

  @TableTest({
    "scenario            | input  ",
    "null value          |        ",
    "empty string        | ''     ",
    "whitespace only     | '   '  ",
    "alphabetic string   | abc    ",
    "alphanumeric string | 12x34  ",
    "multiple decimals   | 3.14.15",
    "multiple signs      | +-5    ",
    "sign only plus      | +      ",
    "sign only minus     | -      ",
    "hexadecimal         | 0x10   ",
    "binary              | 0b1010 ",
    "dot only            | .      ",
    "multiple dots       | ...    ",
    "comma separator     | 1,000  ",
    "currency symbol     | $100   "
  })
  void parseNumberWithInvalidInput(String input) {
    assertNull(parseNumber(input));
  }

  @TableTest({
    "scenario               | input                  ",
    "long overflow positive | 9223372036854775808    ",
    "long overflow negative | -9223372036854775809   ",
    "very large number      | 99999999999999999999999"
  })
  void parseNumberWithOverflow(String input) {
    assertNull(parseNumber(input));
  }

  @Test
  void parseNumberReturnsCorrectTypeBasedOnInputFormat() {
    // Integers return Long
    assertInstanceOf(Long.class, parseNumber("42"));
    assertInstanceOf(Long.class, parseNumber("-100"));

    // Decimals return Double
    assertInstanceOf(Double.class, parseNumber("3.14"));
    assertInstanceOf(Double.class, parseNumber(".5"));

    // Scientific notation returns Double
    assertInstanceOf(Double.class, parseNumber("1e10"));
    assertInstanceOf(Double.class, parseNumber("5E-3"));
  }

  @Test
  void parseNumberHandlesEdgeCasesForTypeChecking() {
    Number intResult = parseNumber("42");
    Number doubleResult = parseNumber("3.14");
    Number sciResult = parseNumber("1e10");

    // Can extract values with proper casting
    assertEquals(Long.valueOf(42L), intResult);
    assertEquals(Double.valueOf(3.14d), doubleResult);
    assertEquals(Double.valueOf(1.0e10d), sciResult);

    // Check types
    assertInstanceOf(Long.class, intResult);
    assertInstanceOf(Double.class, doubleResult);
    assertInstanceOf(Double.class, sciResult);
  }
}

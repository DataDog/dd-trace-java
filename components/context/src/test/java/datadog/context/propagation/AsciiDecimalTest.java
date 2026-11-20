package datadog.context.propagation;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class AsciiDecimalTest {

  @TableTest({
    "Scenario                   | value               ",
    "zero                       | 0                   ",
    "one digit                  | 7                   ",
    "two digits                 | 10                  ",
    "three digits               | 100                 ",
    "int max                    | 2147483647          ",
    "past int max               | 2147483648          ",
    "long max                   | 9223372036854775807 ",
    "minus one                  | -1                  ",
    "long min                   | -9223372036854775808",
    "largest 19-digit unsigned  | -8446744073709551617",
    "smallest 20-digit unsigned | -8446744073709551616"
  })
  void writesTheSameDigitsAsTheJdk(long value) {
    assertEquals(Long.toString(value), new String(AsciiDecimal.of(value), US_ASCII));
    assertEquals(
        Long.toUnsignedString(value), new String(AsciiDecimal.ofUnsigned(value), US_ASCII));
  }

  @TableTest({
    "Scenario | value              ",
    "zero     | 0                  ",
    "max      | 9223372036854775807",
    "negative | -42                "
  })
  void sizesTheArrayToTheDigits(long value) {
    assertEquals(Long.toString(value).length(), AsciiDecimal.of(value).length);
    assertEquals(Long.toUnsignedString(value).length(), AsciiDecimal.ofUnsigned(value).length);
  }
}

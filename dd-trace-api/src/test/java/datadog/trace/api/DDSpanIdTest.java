package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(DDTraceApiTableTestConverters.class)
class DDSpanIdTest {

  @TableTest({
      "scenario          | stringId               | expectedId    ",
      "zero              | '0'                    | 0             ",
      "one               | '1'                    | 1             ",
      "max               | '18446744073709551615' | DDSpanId.MAX  ",
      "long max          | '9223372036854775807'  | Long.MAX_VALUE",
      "long max plus one | '9223372036854775808'  | Long.MIN_VALUE"
  })
  @ParameterizedTest(name = "convert ids from/to String [{index}]")
  void convertIdsFromToString(String stringId, long expectedId) {
    long ddid = DDSpanId.from(stringId);

    assertEquals(expectedId, ddid);
    assertEquals(stringId, DDSpanId.toString(ddid));
  }

  @ParameterizedTest(name = "fail on illegal String [{index}]")
  @NullSource
  @ValueSource(
      strings = {
        "",
        "-1",
        "18446744073709551616",
        "18446744073709551625",
        "184467440737095516150",
        "18446744073709551a1",
        "184467440737095511a"
      })
  void failOnIllegalString(String stringId) {
    assertThrows(NumberFormatException.class, () -> DDSpanId.from(stringId));
  }

  @TableTest({
      "scenario                    | hexId                  | expectedId       ",
      "zero                        | '0'                    | 0                ",
      "one                         | '1'                    | 1                ",
      "max                         | 'ffffffffffffffff'     | DDSpanId.MAX     ",
      "long max                    | '7fffffffffffffff'     | Long.MAX_VALUE   ",
      "long min                    | '8000000000000000'     | Long.MIN_VALUE   ",
      "long min with leading zeros | '00008000000000000000' | Long.MIN_VALUE   ",
      "hex sample                  | 'cafebabe'             | 3405691582       ",
      "fifteen hex digits          | '123456789abcdef'      | 81985529216486895"
  })
  @ParameterizedTest(name = "convert ids from/to hex String [{index}]")
  void convertIdsFromToHexString(String hexId, long expectedId) {
    long ddid = DDSpanId.fromHex(hexId);
    String padded16 =
        hexId.length() <= 16 ? leftPadWithZeros(hexId, 16) : hexId.substring(hexId.length() - 16);
    String normalizedHexId = hexId.length() > 1 ? hexId.replaceFirst("^0+", "") : hexId;
    if (normalizedHexId.isEmpty()) {
      normalizedHexId = "0";
    }

    assertEquals(expectedId, ddid);
    assertEquals(normalizedHexId, DDSpanId.toHexString(ddid));
    assertEquals(padded16, DDSpanId.toHexStringPadded(ddid));
  }

  @TableTest({
      "scenario                                 | hexId              | start | length | lowerCaseOnly | expectedId   ",
      "null input                               |                    | 1     | 1      | false         |              ",
      "empty input                              | ''                 | 1     | 1      | false         |              ",
      "negative start                           | '00'               | -1    | 1      | false         |              ",
      "zero length                              | '00'               | 0     | 0      | false         |              ",
      "single zero at index 0                   | '00'               | 0     | 1      | false         | DDSpanId.ZERO",
      "single zero at index 1                   | '00'               | 1     | 1      | false         | DDSpanId.ZERO",
      "single zero at index 1 duplicate         | '00'               | 1     | 1      | false         | DDSpanId.ZERO",
      "max lower-case                           | 'ffffffffffffffff' | 0     | 16     | true          | DDSpanId.MAX ",
      "upper-case rejected when lower-case only | 'ffffffffffffFfff' | 0     | 16     | true          |              ",
      "upper-case accepted when lower disabled  | 'ffffffffffffFfff' | 0     | 16     | false         | DDSpanId.MAX "
  })
  @ParameterizedTest(name = "convert ids from part of hex String [{index}]")
  void convertIdsFromPartOfHexString(
      String hexId, int start, int length, boolean lowerCaseOnly, Long expectedId) {
    Long parsedId = null;
    try {
      parsedId = DDSpanId.fromHex(hexId, start, length, lowerCaseOnly);
    } catch (NumberFormatException ignored) {
    }

    if (expectedId == null) {
      assertNull(parsedId);
    } else {
      assertNotNull(parsedId);
      assertEquals(expectedId.longValue(), parsedId.longValue());
    }
  }

  @ParameterizedTest(name = "fail on illegal hex String [{index}]")
  @NullSource
  @ValueSource(strings = {"", "-1", "10000000000000000", "ffffffffffffffzf", "fffffffffffffffz"})
  void failOnIllegalHexString(String hexId) {
    assertThrows(NumberFormatException.class, () -> DDSpanId.fromHex(hexId));
  }

  @ParameterizedTest(name = "generate id with {0}")
  @ValueSource(strings = {"RANDOM", "SEQUENTIAL", "SECURE_RANDOM"})
  void generateIdWithStrategy(String strategyName) {
    IdGenerationStrategy strategy = IdGenerationStrategy.fromName(strategyName);
    Set<Long> checked = new HashSet<Long>();

    for (int index = 0; index <= 32768; index++) {
      long spanId = strategy.generateSpanId();
      assertNotEquals(0L, spanId);
      assertFalse(checked.contains(spanId));
      checked.add(spanId);
    }
  }

  private static String leftPadWithZeros(String value, int size) {
    if (value.length() >= size) {
      return value;
    }
    return repeat("0", size - value.length()) + value;
  }

  private static String repeat(String value, int count) {
    StringBuilder builder = new StringBuilder(value.length() * count);
    for (int index = 0; index < count; index++) {
      builder.append(value);
    }
    return builder.toString();
  }
}

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
import org.tabletest.junit.TableTest;

class DDSpanIdTest {

  @TableTest({
    "scenario           | stringId               | expectedId          ",
    "zero               | '0'                    | 0                   ",
    "one                | '1'                    | 1                   ",
    "max                | '18446744073709551615' | -1                  ",
    "long max           | '9223372036854775807'  | 9223372036854775807 ",
    "long max plus one  | '9223372036854775808'  | -9223372036854775808"
  })
  @ParameterizedTest
  void convertIdsFromToString(String stringId, long expectedId) {
    long ddid = DDSpanId.from(stringId);

    assertEquals(expectedId, ddid);
    assertEquals(stringId, DDSpanId.toString(ddid));
  }

  @TableTest({
    "scenario              | stringId               ",
    "null                  |                        ",
    "empty                 | ''                     ",
    "negative one          | '-1'                   ",
    "too large             | '18446744073709551616'",
    "too large variant     | '18446744073709551625'",
    "too large long        | '184467440737095516150'",
    "contains alpha first  | '18446744073709551a1' ",
    "contains alpha last   | '184467440737095511a' "
  })
  @ParameterizedTest
  void failOnIllegalString(String stringId) {
    assertThrows(NumberFormatException.class, () -> DDSpanId.from(stringId));
  }

  @TableTest({
    "scenario                    | hexId                  | expectedId          ",
    "zero                        | '0'                    | 0                   ",
    "one                         | '1'                    | 1                   ",
    "max                         | 'ffffffffffffffff'     | -1                  ",
    "long max                    | '7fffffffffffffff'     | 9223372036854775807 ",
    "long min                    | '8000000000000000'     | -9223372036854775808",
    "long min with leading zeros | '00008000000000000000' | -9223372036854775808",
    "cafebabe                    | 'cafebabe'             | 3405691582          ",
    "fifteen hex digits          | '123456789abcdef'      | 81985529216486895   "
  })
  @ParameterizedTest
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
    "scenario                                | hexId               | start | length | lowerCaseOnly | expectedId",
    "null input                              |                     | 1     | 1      | false         |          ",
    "empty input                             | ''                  | 1     | 1      | false         |          ",
    "negative start                          | '00'                | -1    | 1      | false         |          ",
    "zero length                             | '00'                | 0     | 0      | false         |          ",
    "single zero at index 0                  | '00'                | 0     | 1      | false         | 0        ",
    "single zero at index 1                  | '00'                | 1     | 1      | false         | 0        ",
    "single zero at index 1 duplicate        | '00'                | 1     | 1      | false         | 0        ",
    "max lower-case                          | 'ffffffffffffffff'  | 0     | 16     | true          | -1       ",
    "upper-case rejected when lower-case only| 'ffffffffffffFfff'  | 0     | 16     | true          |          ",
    "upper-case accepted when lower disabled | 'ffffffffffffFfff'  | 0     | 16     | false         | -1       "
  })
  @ParameterizedTest
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

  @TableTest({
    "scenario             | hexId              ",
    "null                 |                    ",
    "empty                | ''                 ",
    "negative one         | '-1'               ",
    "too long             | '10000000000000000'",
    "invalid middle       | 'ffffffffffffffzf' ",
    "invalid tail         | 'fffffffffffffffz' "
  })
  @ParameterizedTest
  void failOnIllegalHexString(String hexId) {
    assertThrows(NumberFormatException.class, () -> DDSpanId.fromHex(hexId));
  }

  @TableTest({
    "scenario      | strategyName ",
    "random        | RANDOM       ",
    "sequential    | SEQUENTIAL   ",
    "secure random | SECURE_RANDOM"
  })
  @ParameterizedTest
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

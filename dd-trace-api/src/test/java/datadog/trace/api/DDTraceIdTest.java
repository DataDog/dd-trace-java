package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(DDTraceApiTableTestConverters.class)
class DDTraceIdTest {

  @TableTest({
    "scenario   | longId               | expectedString        | expectedHex                       ",
    "zero       | 0                    | '0'                   | '00000000000000000000000000000000'",
    "one        | 1                    | '1'                   | '00000000000000000000000000000001'",
    "minus one  | -1                   | '18446744073709551615'| '0000000000000000ffffffffffffffff'",
    "long max   | Long.MAX_VALUE       | '9223372036854775807' | '00000000000000007fffffffffffffff'",
    "long min   | Long.MIN_VALUE       | '9223372036854775808' | '00000000000000008000000000000000'"
  })
  @ParameterizedTest(name = "convert 64-bit ids from/to long and check strings [{index}]")
  void convert64BitIdsFromToLongAndCheckStrings(
      long longId, String expectedString, String expectedHex) {
    DD64bTraceId ddid = DD64bTraceId.from(longId);
    DD64bTraceId expectedId = DD64bTraceId.from(expectedString);
    DDTraceId defaultDdid = DDTraceId.from(longId);

    assertEquals(expectedId, ddid);
    assertEquals(defaultDdid, ddid);
    assertEquals(longId, ddid.toLong());
    assertEquals(0L, ddid.toHighOrderLong());
    assertEquals(expectedString, ddid.toString());
    assertEquals(expectedHex, ddid.toHexString());
  }

  @TableTest({
    "scenario           | stringId               | expectedId           ",
    "zero               | '0'                    | DD64bTraceId.ZERO    ",
    "one                | '1'                    | DD64bTraceId.ONE     ",
    "max                | '18446744073709551615' | DD64bTraceId.MAX     ",
    "long max           | '9223372036854775807'  | DD64bTraceId.LONG_MAX",
    "long max plus one  | '9223372036854775808'  | DD64bTraceId.LONG_MIN"
  })
  @ParameterizedTest(name = "convert 64-bit ids from/to String representation [{index}]")
  void convert64BitIdsFromToStringRepresentation(String stringId, DD64bTraceId expectedId) {
    DD64bTraceId ddid = DD64bTraceId.from(stringId);

    assertEquals(expectedId, ddid);
    assertEquals(stringId, ddid.toString());
  }

  @ParameterizedTest(name = "fail parsing illegal 64-bit id String representation [{index}]")
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
  void failParsingIllegal64BitIdStringRepresentation(String stringId) {
    assertThrows(NumberFormatException.class, () -> DD64bTraceId.from(stringId));
  }

  @TableTest({
    "scenario                    | hexId                  | expectedId           ",
    "zero                        | '0'                    | DD64bTraceId.ZERO    ",
    "one                         | '1'                    | DD64bTraceId.ONE     ",
    "max                         | 'ffffffffffffffff'     | DD64bTraceId.MAX     ",
    "long max                    | '7fffffffffffffff'     | DD64bTraceId.LONG_MAX",
    "long min                    | '8000000000000000'     | DD64bTraceId.LONG_MIN",
    "long min with leading zeros | '00008000000000000000' | DD64bTraceId.LONG_MIN",
    "hex sample                  | 'cafebabe'             | DD64bTraceId.CAFEBABE",
    "fifteen hex digits          | '123456789abcdef'      | DD64bTraceId.HEX     "
  })
  @ParameterizedTest(name = "convert 64-bit ids from/to hex String representation [{index}]")
  void convert64BitIdsFromToHexStringRepresentation(String hexId, DD64bTraceId expectedId) {
    DD64bTraceId ddid = DD64bTraceId.fromHex(hexId);
    String padded16 =
        hexId.length() <= 16 ? leftPadWithZeros(hexId, 16) : hexId.substring(hexId.length() - 16);
    String padded32 = leftPadWithZeros(hexId, 32);

    assertEquals(expectedId, ddid);
    assertEquals(padded32, ddid.toHexString());
    assertEquals(padded16, ddid.toHexStringPadded(16));
    assertEquals(padded32, ddid.toHexStringPadded(32));
  }

  @ParameterizedTest(
      name = "fail parsing illegal 64-bit hexadecimal String representation [{index}]")
  @NullSource
  @ValueSource(strings = {"", "-1", "10000000000000000", "ffffffffffffffzf", "fffffffffffffffz"})
  void failParsingIllegal64BitHexadecimalStringRepresentation(String hexId) {
    assertThrows(NumberFormatException.class, () -> DD64bTraceId.fromHex(hexId));
  }

  @TableTest({
    "scenario                      | highOrderBits        | lowOrderBits         | hexId                             ",
    "both long min                 | Long.MIN_VALUE       | Long.MIN_VALUE       | '80000000000000008000000000000000'",
    "high long min low one         | Long.MIN_VALUE       | 1                    | '80000000000000000000000000000001'",
    "high long min low long max    | Long.MIN_VALUE       | Long.MAX_VALUE       | '80000000000000007fffffffffffffff'",
    "high one low long min         | 1                    | Long.MIN_VALUE       | '00000000000000018000000000000000'",
    "high one low one              | 1                    | 1                    | '00000000000000010000000000000001'",
    "high one low long max         | 1                    | Long.MAX_VALUE       | '00000000000000017fffffffffffffff'",
    "high long max low long min    | Long.MAX_VALUE       | Long.MIN_VALUE       | '7fffffffffffffff8000000000000000'",
    "high long max low one         | Long.MAX_VALUE       | 1                    | '7fffffffffffffff0000000000000001'",
    "high long max low long max    | Long.MAX_VALUE       | Long.MAX_VALUE       | '7fffffffffffffff7fffffffffffffff'",
    "all zeros length one          | 0                    | 0                    | '0'                               ",
    "all zeros length sixteen      | 0                    | 0                    | '0000000000000000'                ",
    "all zeros length seventeen    | 0                    | 0                    | '00000000000000000'               ",
    "all zeros length thirty-two   | 0                    | 0                    | '00000000000000000000000000000000'",
    "low fifteen                   | 0                    | 15                   | 'f'                               ",
    "low minus one                 | 0                    | -1                   | 'ffffffffffffffff'                ",
    "high fifteen low minus one    | 15                   | -1                   | 'fffffffffffffffff'               ",
    "all f                         | -1                   | -1                   | 'ffffffffffffffffffffffffffffffff'",
    "hex literal                   | 1311768467463790320  | 1311768467463790320  | '123456789abcdef0123456789abcdef0'"
  })
  @ParameterizedTest(
      name = "convert 128-bit ids from/to hexadecimal String representation [{index}]")
  void convert128BitIdsFromToHexadecimalStringRepresentation(
      long highOrderBits, long lowOrderBits, String hexId) {
    DDTraceId parsedId = DD128bTraceId.fromHex(hexId);
    DDTraceId id = DD128bTraceId.from(highOrderBits, lowOrderBits);
    String paddedHexId = leftPadWithZeros(hexId, 32);

    assertEquals(id, parsedId);
    assertEquals(paddedHexId, parsedId.toHexString());
    assertEquals(paddedHexId.substring(16, 32), parsedId.toHexStringPadded(16));
    assertEquals(paddedHexId, parsedId.toHexStringPadded(32));
    assertEquals(lowOrderBits, parsedId.toLong());
    assertEquals(highOrderBits, parsedId.toHighOrderLong());
    assertEquals(Long.toUnsignedString(lowOrderBits), parsedId.toString());
  }

  @ParameterizedTest(
      name = "fail parsing illegal 128-bit id hexadecimal String representation [{index}]")
  @NullSource
  @ValueSource(strings = {"", "-1", "-A", "111111111111111111111111111111111", "123ABC", "123abcg"})
  void failParsingIllegal128BitIdHexadecimalStringRepresentation(String hexId) {
    assertThrows(NumberFormatException.class, () -> DD128bTraceId.fromHex(hexId));
  }

  @TableTest({
    "scenario             | hexId                               | start | length | lowerCaseOnly",
    "null string          |                                     | 0     | 0      | true         ",
    "empty string         | ''                                  | 0     | 0      | true         ",
    "out of bound length  | '123456789abcdef0'                  | 0     | 17     | true         ",
    "out of bound end     | '123456789abcdef0'                  | 7     | 10     | true         ",
    "out of bound start   | '123456789abcdef0'                  | 17    | 0      | true         ",
    "invalid minus one    | '-1'                                | 0     | 1      | true         ",
    "invalid minus a      | '-a'                                | 0     | 1      | true         ",
    "invalid character    | '123abcg'                           | 0     | 7      | true         ",
    "invalid upper case A | 'A'                                 | 0     | 1      | true         ",
    "invalid upper case   | '123ABC'                            | 0     | 6      | true         ",
    "too long             | '111111111111111111111111111111111' | 0     | 33     | true         "
  })
  @ParameterizedTest(
      name =
          "fail parsing illegal 128-bit id hexadecimal String representation from partial String [{index}]")
  void failParsingIllegal128BitIdHexadecimalStringRepresentationFromPartialString(
      String hexId, int start, int length, boolean lowerCaseOnly) {
    assertThrows(
        NumberFormatException.class,
        () -> DD128bTraceId.fromHex(hexId, start, length, lowerCaseOnly));
  }

  @Test
  void checkZeroConstantInitialization() {
    DDTraceId zero = DDTraceId.ZERO;
    DDTraceId fromZero = DDTraceId.from(0);

    assertNotNull(zero);
    assertSame(fromZero, zero);
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

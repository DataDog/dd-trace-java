package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DDTraceIdTest {

  @ParameterizedTest(name = "convert 64-bit ids from/to long {1} and check strings")
  @MethodSource("convert64BitIdsFromToLongAndCheckStringsArguments")
  void convert64BitIdsFromToLongAndCheckStrings(
      String displayName,
      long longId,
      DD64bTraceId expectedId,
      String expectedString,
      String expectedHex) {
    DD64bTraceId ddid = DD64bTraceId.from(longId);
    DDTraceId defaultDdid = DDTraceId.from(longId);

    assertEquals(expectedId, ddid);
    assertEquals(defaultDdid, ddid);
    assertEquals(longId, ddid.toLong());
    assertEquals(0L, ddid.toHighOrderLong());
    assertEquals(expectedString, ddid.toString());
    assertEquals(expectedHex, ddid.toHexString());
  }

  static Stream<Arguments> convert64BitIdsFromToLongAndCheckStringsArguments() {
    return Stream.of(
        Arguments.of("zero", 0L, DD64bTraceId.ZERO, "0", repeat("0", 32)),
        Arguments.of("one", 1L, DD64bTraceId.ONE, "1", repeat("0", 31) + "1"),
        Arguments.of(
            "minus one",
            -1L,
            DD64bTraceId.MAX,
            "18446744073709551615",
            repeat("0", 16) + repeat("f", 16)),
        Arguments.of(
            "long max",
            Long.MAX_VALUE,
            DD64bTraceId.from(Long.MAX_VALUE),
            "9223372036854775807",
            repeat("0", 16) + "7" + repeat("f", 15)),
        Arguments.of(
            "long min",
            Long.MIN_VALUE,
            DD64bTraceId.from(Long.MIN_VALUE),
            "9223372036854775808",
            repeat("0", 16) + "8" + repeat("0", 15)));
  }

  @ParameterizedTest(name = "convert 64-bit ids from/to String representation: {1}")
  @MethodSource("convert64BitIdsFromToStringRepresentationArguments")
  void convert64BitIdsFromToStringRepresentation(
      String displayName, String stringId, DD64bTraceId expectedId) {
    DD64bTraceId ddid = DD64bTraceId.from(stringId);

    assertEquals(expectedId, ddid);
    assertEquals(stringId, ddid.toString());
  }

  static Stream<Arguments> convert64BitIdsFromToStringRepresentationArguments() {
    return Stream.of(
        Arguments.of("zero", "0", DD64bTraceId.ZERO),
        Arguments.of("one", "1", DD64bTraceId.ONE),
        Arguments.of("max", "18446744073709551615", DD64bTraceId.MAX),
        Arguments.of("long max", String.valueOf(Long.MAX_VALUE), DD64bTraceId.from(Long.MAX_VALUE)),
        Arguments.of(
            "long max plus one",
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString(),
            DD64bTraceId.from(Long.MIN_VALUE)));
  }

  @ParameterizedTest(name = "fail parsing illegal 64-bit id String representation: {0}")
  @MethodSource("failParsingIllegal64BitIdStringRepresentationArguments")
  void failParsingIllegal64BitIdStringRepresentation(String stringId) {
    assertThrows(NumberFormatException.class, () -> DD64bTraceId.from(stringId));
  }

  static Stream<Arguments> failParsingIllegal64BitIdStringRepresentationArguments() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(""),
        Arguments.of("-1"),
        Arguments.of("18446744073709551616"),
        Arguments.of("18446744073709551625"),
        Arguments.of("184467440737095516150"),
        Arguments.of("18446744073709551a1"),
        Arguments.of("184467440737095511a"));
  }

  @ParameterizedTest(name = "convert 64-bit ids from/to hex String representation: {0}")
  @MethodSource("convert64BitIdsFromToHexStringRepresentationArguments")
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

  static Stream<Arguments> convert64BitIdsFromToHexStringRepresentationArguments() {
    return Stream.of(
        Arguments.of("0", DD64bTraceId.ZERO),
        Arguments.of("1", DD64bTraceId.ONE),
        Arguments.of(repeat("f", 16), DD64bTraceId.MAX),
        Arguments.of("7" + repeat("f", 15), DD64bTraceId.from(Long.MAX_VALUE)),
        Arguments.of("8" + repeat("0", 15), DD64bTraceId.from(Long.MIN_VALUE)),
        Arguments.of(repeat("0", 4) + "8" + repeat("0", 15), DD64bTraceId.from(Long.MIN_VALUE)),
        Arguments.of("cafebabe", DD64bTraceId.from(3405691582L)),
        Arguments.of("123456789abcdef", DD64bTraceId.from(81985529216486895L)));
  }

  @ParameterizedTest(name = "fail parsing illegal 64-bit hexadecimal String representation: {0}")
  @MethodSource("failParsingIllegal64BitHexadecimalStringRepresentationArguments")
  void failParsingIllegal64BitHexadecimalStringRepresentation(String hexId) {
    assertThrows(NumberFormatException.class, () -> DD64bTraceId.fromHex(hexId));
  }

  static Stream<Arguments> failParsingIllegal64BitHexadecimalStringRepresentationArguments() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(""),
        Arguments.of("-1"),
        Arguments.of("1" + repeat("0", 16)),
        Arguments.of(repeat("f", 14) + "zf"),
        Arguments.of(repeat("f", 15) + "z"));
  }

  @ParameterizedTest(name = "convert 128-bit ids from/to hexadecimal String representation {3}")
  @MethodSource("convert128BitIdsFromToHexadecimalStringRepresentationArguments")
  void convert128BitIdsFromToHexadecimalStringRepresentation(
      String displayName, long highOrderBits, long lowOrderBits, String hexId) {
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

  static Stream<Arguments> convert128BitIdsFromToHexadecimalStringRepresentationArguments() {
    return Stream.of(
        Arguments.of(
            "both long min",
            Long.MIN_VALUE,
            Long.MIN_VALUE,
            "8" + repeat("0", 15) + "8" + repeat("0", 15)),
        Arguments.of(
            "high long min low one",
            Long.MIN_VALUE,
            1L,
            "8" + repeat("0", 15) + repeat("0", 15) + "1"),
        Arguments.of(
            "high long min low long max",
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            "8" + repeat("0", 15) + "7" + repeat("f", 15)),
        Arguments.of(
            "high one low long min",
            1L,
            Long.MIN_VALUE,
            repeat("0", 15) + "1" + "8" + repeat("0", 15)),
        Arguments.of("high one low one", 1L, 1L, repeat("0", 15) + "1" + repeat("0", 15) + "1"),
        Arguments.of(
            "high one low long max",
            1L,
            Long.MAX_VALUE,
            repeat("0", 15) + "1" + "7" + repeat("f", 15)),
        Arguments.of(
            "high long max low long min",
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            "7" + repeat("f", 15) + "8" + repeat("0", 15)),
        Arguments.of(
            "high long max low one",
            Long.MAX_VALUE,
            1L,
            "7" + repeat("f", 15) + repeat("0", 15) + "1"),
        Arguments.of(
            "high long max low long max",
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            "7" + repeat("f", 15) + "7" + repeat("f", 15)),
        Arguments.of("all zeros length one", 0L, 0L, repeat("0", 1)),
        Arguments.of("all zeros length sixteen", 0L, 0L, repeat("0", 16)),
        Arguments.of("all zeros length seventeen", 0L, 0L, repeat("0", 17)),
        Arguments.of("all zeros length thirty-two", 0L, 0L, repeat("0", 32)),
        Arguments.of("low fifteen", 0L, 15L, repeat("f", 1)),
        Arguments.of("low minus one", 0L, -1L, repeat("f", 16)),
        Arguments.of("high fifteen low minus one", 15L, -1L, repeat("f", 17)),
        Arguments.of("all f", -1L, -1L, repeat("f", 32)),
        Arguments.of(
            "hex literal",
            1311768467463790320L,
            1311768467463790320L,
            "123456789abcdef0123456789abcdef0"));
  }

  @ParameterizedTest(
      name = "fail parsing illegal 128-bit id hexadecimal String representation: {0}")
  @MethodSource("failParsingIllegal128BitIdHexadecimalStringRepresentationArguments")
  void failParsingIllegal128BitIdHexadecimalStringRepresentation(String hexId) {
    assertThrows(NumberFormatException.class, () -> DD128bTraceId.fromHex(hexId));
  }

  static Stream<Arguments> failParsingIllegal128BitIdHexadecimalStringRepresentationArguments() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(""),
        Arguments.of("-1"),
        Arguments.of("-A"),
        Arguments.of(repeat("1", 33)),
        Arguments.of("123ABC"),
        Arguments.of("123abcg"));
  }

  @ParameterizedTest(
      name =
          "fail parsing illegal 128-bit id hexadecimal String representation from partial String: {1}")
  @MethodSource(
      "failParsingIllegal128BitIdHexadecimalStringRepresentationFromPartialStringArguments")
  void failParsingIllegal128BitIdHexadecimalStringRepresentationFromPartialString(
      String displayName, String hexId, int start, int length, boolean lowerCaseOnly) {
    assertThrows(
        NumberFormatException.class,
        () -> DD128bTraceId.fromHex(hexId, start, length, lowerCaseOnly));
  }

  static Stream<Arguments>
      failParsingIllegal128BitIdHexadecimalStringRepresentationFromPartialStringArguments() {
    return Stream.of(
        Arguments.of("null string", null, 0, 0, true),
        Arguments.of("empty string", "", 0, 0, true),
        Arguments.of("out of bound length", "123456789abcdef0", 0, 17, true),
        Arguments.of("out of bound end", "123456789abcdef0", 7, 10, true),
        Arguments.of("out of bound start", "123456789abcdef0", 17, 0, true),
        Arguments.of("invalid minus one", "-1", 0, 1, true),
        Arguments.of("invalid minus a", "-a", 0, 1, true),
        Arguments.of("invalid character", "123abcg", 0, 7, true),
        Arguments.of("invalid upper case A", "A", 0, 1, true),
        Arguments.of("invalid upper case ABC", "123ABC", 0, 6, true),
        Arguments.of("too long", repeat("1", 33), 0, 33, true));
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

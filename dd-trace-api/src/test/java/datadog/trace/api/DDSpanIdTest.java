package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DDSpanIdTest {

  @ParameterizedTest(name = "convert ids from/to String {0}")
  @MethodSource("convertIdsFromToStringArguments")
  void convertIdsFromToString(String displayName, String stringId, long expectedId) {
    long ddid = DDSpanId.from(stringId);

    assertEquals(expectedId, ddid);
    assertEquals(stringId, DDSpanId.toString(ddid));
  }

  static Stream<Arguments> convertIdsFromToStringArguments() {
    return Stream.of(
        Arguments.of("zero", "0", 0L),
        Arguments.of("one", "1", 1L),
        Arguments.of("max", "18446744073709551615", DDSpanId.MAX),
        Arguments.of("long max", String.valueOf(Long.MAX_VALUE), Long.MAX_VALUE),
        Arguments.of(
            "long max plus one",
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString(),
            Long.MIN_VALUE));
  }

  @ParameterizedTest(name = "fail on illegal String {0}")
  @MethodSource("failOnIllegalStringArguments")
  void failOnIllegalString(String stringId) {
    assertThrows(NumberFormatException.class, () -> DDSpanId.from(stringId));
  }

  static Stream<Arguments> failOnIllegalStringArguments() {
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

  @ParameterizedTest(name = "convert ids from/to hex String {0}")
  @MethodSource("convertIdsFromToHexStringArguments")
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

  static Stream<Arguments> convertIdsFromToHexStringArguments() {
    return Stream.of(
        Arguments.of("0", 0L),
        Arguments.of("1", 1L),
        Arguments.of(repeat("f", 16), DDSpanId.MAX),
        Arguments.of("7" + repeat("f", 15), Long.MAX_VALUE),
        Arguments.of("8" + repeat("0", 15), Long.MIN_VALUE),
        Arguments.of(repeat("0", 4) + "8" + repeat("0", 15), Long.MIN_VALUE),
        Arguments.of("cafebabe", 3405691582L),
        Arguments.of("123456789abcdef", 81985529216486895L));
  }

  @ParameterizedTest(name = "convert ids from part of hex String {0}")
  @MethodSource("convertIdsFromPartOfHexStringArguments")
  void convertIdsFromPartOfHexString(
      String displayName,
      String hexId,
      int start,
      int length,
      boolean lowerCaseOnly,
      Long expectedId) {
    Long parsedId = null;
    try {
      parsedId = DDSpanId.fromHex(hexId, start, length, lowerCaseOnly);
    } catch (NumberFormatException ignored) {
      // Validate behavior through parsedId remaining null.
    }

    if (expectedId == null) {
      assertNull(parsedId);
    } else {
      assertNotNull(parsedId);
      assertEquals(expectedId.longValue(), parsedId.longValue());
    }
  }

  static Stream<Arguments> convertIdsFromPartOfHexStringArguments() {
    return Stream.of(
        Arguments.of("null input", null, 1, 1, false, null),
        Arguments.of("empty input", "", 1, 1, false, null),
        Arguments.of("negative start", "00", -1, 1, false, null),
        Arguments.of("zero length", "00", 0, 0, false, null),
        Arguments.of("single zero at index 0", "00", 0, 1, false, DDSpanId.ZERO),
        Arguments.of("single zero at index 1", "00", 1, 1, false, DDSpanId.ZERO),
        Arguments.of("single zero at index 1 duplicate", "00", 1, 1, false, DDSpanId.ZERO),
        Arguments.of("max lower-case", repeat("f", 16), 0, 16, true, DDSpanId.MAX),
        Arguments.of(
            "upper-case rejected when lower-case only",
            repeat("f", 12) + "Ffff",
            0,
            16,
            true,
            null),
        Arguments.of(
            "upper-case accepted when lower-case disabled",
            repeat("f", 12) + "Ffff",
            0,
            16,
            false,
            DDSpanId.MAX));
  }

  @ParameterizedTest(name = "fail on illegal hex String {0}")
  @MethodSource("failOnIllegalHexStringArguments")
  void failOnIllegalHexString(String hexId) {
    assertThrows(NumberFormatException.class, () -> DDSpanId.fromHex(hexId));
  }

  static Stream<Arguments> failOnIllegalHexStringArguments() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(""),
        Arguments.of("-1"),
        Arguments.of("1" + repeat("0", 16)),
        Arguments.of(repeat("f", 14) + "zf"),
        Arguments.of(repeat("f", 15) + "z"));
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

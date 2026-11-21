package datadog.trace.api.internal.util;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.util.Arrays;
import java.util.Locale;

/**
 * Utility class with common long decimal and hexadecimal parsing, and {@link String} creation
 * methods.
 */
public class LongStringUtils {
  private static final long MAX_FIRST_PART = 0x1999999999999999L; // Max unsigned 64 bits / 10

  private LongStringUtils() {}

  /**
   * Parse the hex representation of the unsigned 64 bit long from the {@code String}.
   *
   * @param s String in hexadecimal of unsigned 64-bits long.
   * @return long
   * @throws NumberFormatException
   */
  public static long parseUnsignedLongHex(CharSequence s) throws NumberFormatException {
    return LongStringUtils.parseUnsignedLongHex(s, 0, s == null ? 0 : s.length(), false);
  }

  /**
   * Parse the hex representation of the unsigned 64 bit long from the {@code String}.
   *
   * @param s String in hex of unsigned 64 bits
   * @param start the start index of the hex value
   * @param len the len of the hex value
   * @param lowerCaseOnly if the allowed hex characters are lower case only
   * @return long
   * @throws NumberFormatException
   */
  public static long parseUnsignedLongHex(CharSequence s, int start, int len, boolean lowerCaseOnly)
      throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    if (len > 0 && start >= 0 && start + len <= s.length()) {
      if (len > 16 && (len - firstNonZeroCharacter(s, start)) > 16) {
        // Unsigned 64 bits max is 16 digits, so this always overflows
        throw numberFormatOutOfLongRange(s);
      }
      long result = 0;
      int ok = 0;
      for (int i = 0; i < len && ok >= 0; i++, start++) {
        char c = s.charAt(start);
        int d = Character.digit(c, 16);
        if (lowerCaseOnly && Character.isUpperCase(c)) {
          ok = -1;
        }
        ok |= d;
        result = result << 4 | d;
      }
      if (ok < 0) {
        throw new NumberFormatException("Illegal character in " + s.subSequence(start, len));
      }
      return result;
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  /**
   * Like {@link #parseUnsignedLongHex(CharSequence, int, int, boolean)}, but never throws: anything
   * it would reject, such as an illegal character or more than 16 significant digits, yields {@code
   * 0} instead. For parsing input that is often malformed, such as propagation headers, without the
   * cost of an exception.
   *
   * <p>{@code 0} is also the value of an all-zero input. Callers that accept a zero value must tell
   * the two apart themselves; for most ids zero is already invalid.
   *
   * @param s String in hex of unsigned 64 bits
   * @param start the start index of the hex value
   * @param len the len of the hex value
   * @param lowerCaseOnly if the allowed hex characters are lower case only
   * @return the parsed value, or {@code 0} if {@code s} is not a valid unsigned 64-bit hex value
   */
  public static long parseUnsignedLongHexOrZero(
      CharSequence s, int start, int len, boolean lowerCaseOnly) {
    if (s == null || len <= 0 || start < 0 || start + len > s.length()) {
      return 0;
    }
    long result = 0;
    int significantDigits = 0;
    for (int i = start, end = start + len; i < end; i++) {
      char c = s.charAt(i);
      int digit = Character.digit(c, 16);
      if (digit < 0 || (lowerCaseOnly && Character.isUpperCase(c))) {
        return 0;
      }
      // Only an ASCII '0' is padding, as in parseUnsignedLongHex: other zero digits count.
      if ((significantDigits > 0 || c != '0') && ++significantDigits > 16) {
        // Unsigned 64 bits max is 16 digits, so this always overflows
        return 0;
      }
      result = result << 4 | digit;
    }
    return result;
  }

  private static int firstNonZeroCharacter(CharSequence s, int start) {
    int firstNonZero = start;
    for (; firstNonZero < s.length(); firstNonZero++) {
      if (s.charAt(firstNonZero) != '0') break;
    }
    return firstNonZero;
  }

  /**
   * Parse the decimal representation of the unsigned 64 bit long from the {@code String}.
   *
   * @param s String in decimal of unsigned 64-bits long.
   * @return long
   * @throws NumberFormatException
   */
  public static long parseUnsignedLong(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s can't be null");
    }

    int len = s.length();
    if (len > 0) {
      char firstChar = s.charAt(0);
      if (firstChar == '-') {
        throw new NumberFormatException(
            String.format(Locale.ROOT, "Illegal leading minus sign on unsigned string %s.", s));
      } else {
        if (len <= 18) { // Signed 64 bits max is 19 digits, so this always fits
          return Long.parseLong(s);
        }
        return parseLongUnsignedDigits(s, 0, len);
      }
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  /**
   * {@link #parseUnsignedLong(String)} for the chars {@code [start, end)} of {@code s}: the same
   * result, or a {@code NumberFormatException}, as for the same chars in a {@code String}, without
   * making one.
   */
  public static long parseUnsignedLong(CharSequence s, int start, int end)
      throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s can't be null");
    }

    int len = end - start;
    if (len > 0) {
      char firstChar = s.charAt(start);
      if (firstChar == '-') {
        throw new NumberFormatException(
            String.format(
                Locale.ROOT,
                "Illegal leading minus sign on unsigned string %s.",
                s.subSequence(start, end)));
      } else {
        if (len <= 18) { // Signed 64 bits max is 19 digits, so this always fits
          return parseLong(s, start, end);
        }
        return parseLongUnsignedDigits(s, start, end);
      }
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  /** The 19 or more digits {@code [start, end)} of {@code s} as an unsigned long. */
  private static long parseLongUnsignedDigits(CharSequence s, int start, int end) {
    int len = end - start;
    if (len > 20) { // Unsigned 64 bits max is 20 digits, so this always overflows
      throw numberFormatOutOfLongRange(s.subSequence(start, end));
    }
    // Now do the first part and the last character
    long first = 0;
    int ok = 0;
    for (int i = start; i < end - 1; i++) {
      char c = s.charAt(i);
      int d = Character.digit(c, 10);
      ok |= d;
      first = first * 10 + d;
    }
    int last = Character.digit(s.charAt(end - 1), 10);
    ok |= last;
    if (ok < 0) {
      throw new NumberFormatException("Illegal character in " + s.subSequence(start, end));
    }
    if (Long.compareUnsigned(first, MAX_FIRST_PART) > 0) {
      throw numberFormatOutOfLongRange(s.subSequence(start, end));
    }
    long guard = first * 10;
    long result = guard + last;
    if (guard < 0 && result >= 0) {
      throw numberFormatOutOfLongRange(s.subSequence(start, end));
    }
    return result;
  }

  /**
   * {@link Long#parseLong(String)} for the chars {@code [start, end)} of {@code s}, in base 10: the
   * same result, or a {@code NumberFormatException}, as for the same chars in a {@code String}.
   * Java 8 has no range-based {@code CharSequence} overload.
   */
  public static long parseLong(CharSequence s, int start, int end) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s can't be null");
    }
    if (start >= end) {
      throw new NumberFormatException("Empty input string");
    }
    boolean negative = false;
    long limit = -Long.MAX_VALUE;
    int i = start;
    char firstChar = s.charAt(i);
    if (firstChar < '0') { // possible leading "+" or "-"
      if (firstChar == '-') {
        negative = true;
        limit = Long.MIN_VALUE;
      } else if (firstChar != '+') {
        throw forInput(s, start, end);
      }
      if (end - start == 1) { // a lone "+" or "-"
        throw forInput(s, start, end);
      }
      i++;
    }
    long multmin = limit / 10;
    long result = 0;
    while (i < end) {
      // Accumulating negatively avoids surprises near MAX_VALUE
      int digit = Character.digit(s.charAt(i++), 10);
      if (digit < 0 || result < multmin) {
        throw forInput(s, start, end);
      }
      result *= 10;
      if (result < limit + digit) {
        throw forInput(s, start, end);
      }
      result -= digit;
    }
    return negative ? result : -result;
  }

  /**
   * {@link Integer#parseInt(String)} for the chars {@code [start, end)} of {@code s}: the same
   * result, or a {@code NumberFormatException}, as for the same chars in a {@code String}. Java 8
   * has no range-based {@code CharSequence} overload.
   */
  public static int parseInt(CharSequence s, int start, int end) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s can't be null");
    }
    if (start >= end) {
      throw new NumberFormatException("Empty input string");
    }
    boolean negative = false;
    int limit = -Integer.MAX_VALUE;
    int i = start;
    char firstChar = s.charAt(i);
    if (firstChar < '0') { // possible leading "+" or "-"
      if (firstChar == '-') {
        negative = true;
        limit = Integer.MIN_VALUE;
      } else if (firstChar != '+') {
        throw forInput(s, start, end);
      }
      if (end - start == 1) { // a lone "+" or "-"
        throw forInput(s, start, end);
      }
      i++;
    }
    int multmin = limit / 10;
    int result = 0;
    while (i < end) {
      // Accumulating negatively avoids surprises near MAX_VALUE
      int digit = Character.digit(s.charAt(i++), 10);
      if (digit < 0 || result < multmin) {
        throw forInput(s, start, end);
      }
      result *= 10;
      if (result < limit + digit) {
        throw forInput(s, start, end);
      }
      result -= digit;
    }
    return negative ? result : -result;
  }

  private static NumberFormatException forInput(CharSequence s, int start, int end) {
    return new NumberFormatException("For input string: \"" + s.subSequence(start, end) + "\"");
  }

  /**
   * Creates a {@code NumberFormatException} with a consistent error message.
   *
   * @param s the {@code String} that exceeds the range of a {@code Long}
   * @return NumberFormatException
   */
  public static NumberFormatException numberFormatOutOfLongRange(CharSequence s) {
    return new NumberFormatException(
        String.format(Locale.ROOT, "String value %s exceeds range of unsigned long.", s));
  }

  private static final byte[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  public static String toHexStringPadded(long id, int size) {
    byte[] bytes = allocatePaddedHexStringBytes(size);
    fillStringBytesWithPaddedHexId(id, 0, bytes.length, bytes);
    return new String(bytes, US_ASCII);
  }

  public static String toHexStringPadded(long highOrderBits, long lowOrderBits, int size) {
    if (size <= 16) {
      // Fallback to only one id formatting
      return toHexStringPadded(lowOrderBits, size);
    }
    byte[] bytes = allocatePaddedHexStringBytes(size);
    fillStringBytesWithPaddedHexId(highOrderBits, 0, 16, bytes);
    fillStringBytesWithPaddedHexId(lowOrderBits, 16, 16, bytes);
    return new String(bytes, US_ASCII);
  }

  private static byte[] allocatePaddedHexStringBytes(int size) {
    if (size > 16) {
      size = 32;
    } else if (size < 16) {
      size = 16;
    }
    return new byte[size];
  }

  private static void fillStringBytesWithPaddedHexId(long id, int index, int size, byte[] bytes) {
    int nibbleCount = Long.numberOfLeadingZeros(id) >>> 2;
    Arrays.fill(bytes, index, index + (size - 16) + nibbleCount, (byte) '0');
    for (int i = 0; i < 16 - nibbleCount; i++) {
      int b = (int) (id & 0xF);
      bytes[index + size - 1 - i] = HEX_DIGITS[b];
      id >>>= 4;
    }
  }
}

package datadog.trace.api.internal.util;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.util.Arrays;

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

  private static int firstNonZeroCharacter(CharSequence s, int start) {
    int firstNonZero = start;
    for (; firstNonZero < s.length(); firstNonZero++) {
      if (s.charAt(firstNonZero) != '0') break;
    }
    return firstNonZero;
  }

  public static long parseUnsignedLong(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s can't be null");
    }

    int len = s.length();
    if (len > 0) {
      char firstChar = s.charAt(0);
      if (firstChar == '-') {
        throw new NumberFormatException(
            String.format("Illegal leading minus sign on unsigned string %s.", s));
      } else {
        if (len <= 18) { // Signed 64 bits max is 19 digits, so this always fits
          return Long.parseLong(s);
        } else if (len > 20) { // Unsigned 64 bits max is 20 digits, so this always overflows
          throw numberFormatOutOfLongRange(s);
        }
        // Now do the first part and the last character
        long first = 0;
        int ok = 0;
        for (int i = 0; i < len - 1; i++) {
          char c = s.charAt(i);
          int d = Character.digit(c, 10);
          ok |= d;
          first = first * 10 + d;
        }
        int last = Character.digit(s.charAt(len - 1), 10);
        ok |= last;
        if (ok < 0) {
          throw new NumberFormatException("Illegal character in " + s);
        }
        if (first > MAX_FIRST_PART) {
          throw numberFormatOutOfLongRange(s);
        }
        long guard = first * 10;
        long result = guard + last;
        if (guard < 0 && result >= 0) {
          throw numberFormatOutOfLongRange(s);
        }
        return result;
      }
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  /**
   * Creates a {@code NumberFormatException} with a consistent error message.
   *
   * @param s the {@code String} that exceeds the range of a {@code Long}
   * @return NumberFormatException
   */
  public static NumberFormatException numberFormatOutOfLongRange(CharSequence s) {
    return new NumberFormatException(
        String.format("String value %s exceeds range of unsigned long.", s));
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

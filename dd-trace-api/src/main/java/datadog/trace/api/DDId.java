package datadog.trace.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Utility class with common parsing and {@code String} creation methods used for Trace and Span
 * ids.
 */
final class DDId {

  // Don't allow instances
  private DDId() {}

  private static int firstNonZeroCharacter(String s, int start) {
    int firstNonZero = start;
    for (; firstNonZero < s.length(); firstNonZero++) {
      if (s.charAt(firstNonZero) != '0') break;
    }
    return firstNonZero;
  }

  private static NumberFormatException numberFormatOutOfRange(String s) {
    return new NumberFormatException(
        String.format("String value %s exceeds range of unsigned long.", s));
  }

  private static final long MAX_FIRST_PART = 0x1999999999999999L; // Max unsigned 64 bits / 10

  static long parseUnsignedLong(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
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
          throw numberFormatOutOfRange(s);
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
          throw numberFormatOutOfRange(s);
        }
        long guard = first * 10;
        long result = guard + last;
        if (guard < 0 && result >= 0) {
          throw numberFormatOutOfRange(s);
        }
        return result;
      }
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  static long parseUnsignedLongHex(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    return parseUnsignedLongHex(s, 0, s.length());
  }

  static long parseUnsignedLongHex(String s, int start, int len) throws NumberFormatException {
    if (len > 0) {
      if (len > 16 && (len - firstNonZeroCharacter(s, start)) > 16) {
        // Unsigned 64 bits max is 16 digits, so this always overflows
        throw numberFormatOutOfRange(s);
      }
      long result = 0;
      int ok = 0;
      for (int i = 0; i < len; i++, start++) {
        char c = s.charAt(start);
        int d = Character.digit(c, 16);
        ok |= d;
        result = result << 4 | d;
      }
      if (ok < 0) {
        throw new NumberFormatException("Illegal character in " + s);
      }
      return result;
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  private static final byte[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  static String toHexStringPadded(long id, int size) {
    if (size > 16) {
      size = 32;
    } else if (size < 16) {
      size = 16;
    }
    byte[] bytes = new byte[size];
    long remaining = id;
    int nibbleCount = Long.numberOfLeadingZeros(remaining) >>> 2;
    Arrays.fill(bytes, 0, (size - 16) + nibbleCount, (byte) '0');
    for (int i = 0; i < 16 - nibbleCount; i++) {
      int b = (int) (remaining & 0xF);
      bytes[size - 1 - i] = HEX_DIGITS[b];
      remaining >>>= 4;
    }
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}

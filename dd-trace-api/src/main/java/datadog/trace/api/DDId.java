package datadog.trace.api;

import static datadog.trace.api.internal.util.HexStringUtils.numberFormatOutOfLongRange;

import datadog.trace.api.internal.util.HexStringUtils;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Utility class with common parsing and {@code String} creation methods used for Trace and Span
 * ids.
 */
final class DDId {

  // Don't allow instances
  private DDId() {}

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

  static long parseUnsignedLongHex(String s) throws NumberFormatException {
    return HexStringUtils.parseUnsignedLongHex(s, 0, s == null ? 0 : s.length(), false);
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

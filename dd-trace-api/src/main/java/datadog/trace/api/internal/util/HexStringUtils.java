package datadog.trace.api.internal.util;

public class HexStringUtils {

  private HexStringUtils() {}

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
  public static long parseUnsignedLongHex(String s, int start, int len, boolean lowerCaseOnly)
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
        throw new NumberFormatException("Illegal character in " + s.substring(start, len));
      }
      return result;
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  private static int firstNonZeroCharacter(String s, int start) {
    int firstNonZero = start;
    for (; firstNonZero < s.length(); firstNonZero++) {
      if (s.charAt(firstNonZero) != '0') break;
    }
    return firstNonZero;
  }

  /**
   * Creates a {@code NumberFormatException} with a consistent error message.
   *
   * @param s the {@code String} that exceeds the range of a {@code Long}
   * @return NumberFormatException
   */
  public static NumberFormatException numberFormatOutOfLongRange(String s) {
    return new NumberFormatException(
        String.format("String value %s exceeds range of unsigned long.", s));
  }
}

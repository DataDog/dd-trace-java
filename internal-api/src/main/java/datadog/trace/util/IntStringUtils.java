package datadog.trace.util;

import javax.annotation.Nullable;

/** Exception-free {@code int} parsing, the {@code int} counterpart of {@code LongStringUtils}. */
public final class IntStringUtils {

  private IntStringUtils() {}

  /**
   * Parses a non-negative decimal {@code int} from the whole sequence without throwing.
   *
   * @return the parsed value, or {@code -1} if {@code s} is null or empty, contains anything other
   *     than ASCII digits (including a sign), or overflows {@code int}
   */
  public static int parseNonNegativeInt(@Nullable CharSequence s) {
    return s == null ? -1 : parseNonNegativeInt(s, 0, s.length());
  }

  /**
   * Parses a non-negative decimal {@code int} from the {@code len} characters of {@code s} starting
   * at {@code start}, without throwing or allocating.
   *
   * @return the parsed value, or {@code -1} if the range is empty or out of bounds, contains
   *     anything other than ASCII digits (including a sign), or overflows {@code int}
   */
  public static int parseNonNegativeInt(@Nullable CharSequence s, int start, int len) {
    if (s == null || start < 0 || len <= 0 || start > s.length() - len) {
      return -1;
    }
    int result = 0;
    for (int i = start, end = start + len; i < end; i++) {
      int digit = s.charAt(i) - '0';
      if (digit < 0 || digit > 9 || result > (Integer.MAX_VALUE - digit) / 10) {
        return -1;
      }
      result = result * 10 + digit;
    }
    return result;
  }
}

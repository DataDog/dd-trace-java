package datadog.trace.util;

import javax.annotation.Nullable;

/** Exception-free {@code int} parsing, the {@code int} counterpart of {@code LongStringUtils}. */
public final class IntStringUtils {

  /**
   * Accept a single leading {@code '+'}, as {@link Integer#parseInt(String)} does. Not every
   * grammar allows it, but callers that must match lenient parsers elsewhere may need it.
   */
  public static final boolean ALLOW_LEADING_PLUS = true;

  /** Accept ASCII digits only; any sign makes the input invalid. */
  public static final boolean REJECT_LEADING_PLUS = false;

  private IntStringUtils() {}

  /**
   * Parses a non-negative decimal {@code int} from the whole sequence without throwing.
   *
   * @param allowLeadingPlus {@link #ALLOW_LEADING_PLUS} or {@link #REJECT_LEADING_PLUS}
   * @return the parsed value, or {@code -1} if {@code s} is null or empty, contains anything other
   *     than ASCII digits (apart from an allowed leading {@code '+'}), or overflows {@code int}
   */
  public static int parseNonNegativeInt(@Nullable CharSequence s, boolean allowLeadingPlus) {
    return s == null ? -1 : parseNonNegativeInt(s, 0, s.length(), allowLeadingPlus);
  }

  /**
   * Parses a non-negative decimal {@code int} from the {@code len} characters of {@code s} starting
   * at {@code start}, without throwing or allocating.
   *
   * @param allowLeadingPlus {@link #ALLOW_LEADING_PLUS} or {@link #REJECT_LEADING_PLUS}
   * @return the parsed value, or {@code -1} if the range is empty or out of bounds, contains
   *     anything other than ASCII digits (apart from an allowed leading {@code '+'}), or overflows
   *     {@code int}
   */
  public static int parseNonNegativeInt(
      @Nullable CharSequence s, int start, int len, boolean allowLeadingPlus) {
    if (s == null || start < 0 || len <= 0 || start > s.length() - len) {
      return -1;
    }
    int i = start;
    final int end = start + len;
    if (allowLeadingPlus && s.charAt(i) == '+' && ++i == end) {
      return -1; // a sign with no digits
    }
    int result = 0;
    for (; i < end; i++) {
      int digit = s.charAt(i) - '0';
      if (digit < 0 || digit > 9 || result > (Integer.MAX_VALUE - digit) / 10) {
        return -1;
      }
      result = result * 10 + digit;
    }
    return result;
  }
}

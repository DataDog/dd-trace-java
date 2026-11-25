package datadog.trace.bootstrap.jit;

/**
 * Replacements for parsing a number from a substring, such as {@code
 * Integer.parseInt(s.substring(a, b))}: they parse the range in place instead of building the
 * substring.
 *
 * <p>The fast path takes the indexes {@code substring} accepts, and an optional sign followed by at
 * most 9 ASCII digits for an {@code int} (18 for a {@code long}), which can never overflow.
 * Anything else (other digits, which {@code parseInt} also accepts, an empty or sign-only range,
 * longer numbers, or bad indexes) runs the original expression, so results and exceptions are the
 * JDK's own.
 */
public final class NumberFusions {
  private NumberFusions() {}

  private static final int INT_DIGITS = 9;
  private static final int LONG_DIGITS = 18;

  public static int parseIntSubstring(String s, int begin, int end) {
    if (null != s
        && begin >= 0
        && end <= s.length()
        && begin <= end
        && simple(s, begin, end, INT_DIGITS)) {
      return (int) parse(s, begin, end);
    }
    return Integer.parseInt(s.substring(begin, end));
  }

  public static int parseIntSubstring(String s, int begin) {
    if (null != s
        && begin >= 0
        && begin <= s.length()
        && simple(s, begin, s.length(), INT_DIGITS)) {
      return (int) parse(s, begin, s.length());
    }
    return Integer.parseInt(s.substring(begin));
  }

  /** {@code Integer.valueOf(String)} is {@code Integer.valueOf(Integer.parseInt(String))}. */
  public static Integer valueOfSubstring(String s, int begin, int end) {
    return Integer.valueOf(parseIntSubstring(s, begin, end));
  }

  public static Integer valueOfSubstring(String s, int begin) {
    return Integer.valueOf(parseIntSubstring(s, begin));
  }

  public static long parseLongSubstring(String s, int begin, int end) {
    if (null != s
        && begin >= 0
        && end <= s.length()
        && begin <= end
        && simple(s, begin, end, LONG_DIGITS)) {
      return parse(s, begin, end);
    }
    return Long.parseLong(s.substring(begin, end));
  }

  public static long parseLongSubstring(String s, int begin) {
    if (null != s
        && begin >= 0
        && begin <= s.length()
        && simple(s, begin, s.length(), LONG_DIGITS)) {
      return parse(s, begin, s.length());
    }
    return Long.parseLong(s.substring(begin));
  }

  /** An optional '+' or '-' followed by 1 to {@code maxDigits} ASCII digits. */
  private static boolean simple(String s, int begin, int end, int maxDigits) {
    int i = begin;
    if (i < end) {
      char first = s.charAt(i);
      if (first == '-' || first == '+') {
        i++;
      }
    }
    int digits = end - i;
    if (digits < 1 || digits > maxDigits) {
      return false;
    }
    for (; i < end; i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /** Parses a range that {@link #simple} accepted. */
  private static long parse(String s, int begin, int end) {
    int i = begin;
    boolean negative = false;
    char first = s.charAt(i);
    if (first == '-') {
      negative = true;
      i++;
    } else if (first == '+') {
      i++;
    }
    long result = 0;
    for (; i < end; i++) {
      result = result * 10 + (s.charAt(i) - '0');
    }
    return negative ? -result : result;
  }
}

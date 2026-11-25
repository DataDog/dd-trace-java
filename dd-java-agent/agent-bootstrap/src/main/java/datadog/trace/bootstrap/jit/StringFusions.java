package datadog.trace.bootstrap.jit;

import java.util.Locale;

/**
 * Replacements for short call chains on a {@code String} whose intermediate string never escapes,
 * such as {@code s.toLowerCase().equals(t)} or {@code s.substring(i).startsWith(p)}: they give the
 * same result without building the intermediate string.
 *
 * <p>Case conversion: the fast path only handles strings whose case mapping is one char to one char
 * and doesn't depend on context. For lower case that's every char up to {@code U+00FF}; for upper
 * case only ASCII ({@code ß}, {@code µ} and {@code ÿ} map outside Latin-1 or to two chars). Like
 * the JDK, Turkish, Azerbaijani and Lithuanian locales use special casing, so they always go to the
 * original expression. Anything else (other chars, a {@code null} argument) runs the original
 * expression, which throws its own exceptions.
 *
 * <p>Substrings: an index the original {@code substring} would reject calls that {@code substring},
 * so the exception is the JDK's own.
 */
public final class StringFusions {
  private StringFusions() {}

  // ---- s.toLowerCase()/toUpperCase() then equals/startsWith/endsWith

  public static boolean lowerEquals(String s, Object t) {
    return caseEquals(s, Locale.getDefault(), t, false);
  }

  public static boolean lowerEquals(String s, Locale locale, Object t) {
    return caseEquals(s, locale, t, false);
  }

  public static boolean upperEquals(String s, Object t) {
    return caseEquals(s, Locale.getDefault(), t, true);
  }

  public static boolean upperEquals(String s, Locale locale, Object t) {
    return caseEquals(s, locale, t, true);
  }

  public static boolean lowerStartsWith(String s, String p) {
    return caseRegion(s, Locale.getDefault(), p, false, true);
  }

  public static boolean lowerStartsWith(String s, Locale locale, String p) {
    return caseRegion(s, locale, p, false, true);
  }

  public static boolean upperStartsWith(String s, String p) {
    return caseRegion(s, Locale.getDefault(), p, true, true);
  }

  public static boolean upperStartsWith(String s, Locale locale, String p) {
    return caseRegion(s, locale, p, true, true);
  }

  public static boolean lowerEndsWith(String s, String p) {
    return caseRegion(s, Locale.getDefault(), p, false, false);
  }

  public static boolean lowerEndsWith(String s, Locale locale, String p) {
    return caseRegion(s, locale, p, false, false);
  }

  public static boolean upperEndsWith(String s, String p) {
    return caseRegion(s, Locale.getDefault(), p, true, false);
  }

  public static boolean upperEndsWith(String s, Locale locale, String p) {
    return caseRegion(s, locale, p, true, false);
  }

  // ---- lit.equals(s.toLowerCase()) and friends: the receiver comes first

  public static boolean equalsLower(String receiver, String s) {
    return equalsCase(receiver, s, Locale.getDefault(), false);
  }

  public static boolean equalsLower(String receiver, String s, Locale locale) {
    return equalsCase(receiver, s, locale, false);
  }

  public static boolean equalsUpper(String receiver, String s) {
    return equalsCase(receiver, s, Locale.getDefault(), true);
  }

  public static boolean equalsUpper(String receiver, String s, Locale locale) {
    return equalsCase(receiver, s, locale, true);
  }

  private static boolean caseEquals(String s, Locale locale, Object t, boolean upper) {
    if (null != s && null != locale && simpleCasing(locale)) {
      if (!(t instanceof String)) {
        return false; // String.equals with a non-String (or null) is false
      }
      String other = (String) t;
      int length = s.length();
      if (other.length() == length && mapsOneToOne(s, upper)) {
        return regionMatchesMapped(s, 0, other, 0, length, upper);
      }
      if (other.length() != length && mapsOneToOne(s, upper)) {
        return false; // a one-to-one mapping keeps the length
      }
    }
    return original(s, locale, upper).equals(t);
  }

  private static boolean caseRegion(
      String s, Locale locale, String p, boolean upper, boolean start) {
    if (null != s
        && null != locale
        && null != p
        && simpleCasing(locale)
        && mapsOneToOne(s, upper)) {
      int length = s.length();
      int prefix = p.length();
      if (prefix > length) {
        return false;
      }
      return regionMatchesMapped(s, start ? 0 : length - prefix, p, 0, prefix, upper);
    }
    String mapped = original(s, locale, upper);
    return start ? mapped.startsWith(p) : mapped.endsWith(p);
  }

  private static boolean equalsCase(String receiver, String s, Locale locale, boolean upper) {
    if (null != receiver && null != s && null != locale && simpleCasing(locale)) {
      if (mapsOneToOne(s, upper)) {
        int length = s.length();
        return receiver.length() == length && regionMatchesMapped(s, 0, receiver, 0, length, upper);
      }
    }
    // the original evaluates s's conversion first, then calls equals on the receiver
    String mapped = original(s, locale, upper);
    return receiver.equals(mapped);
  }

  /** The original conversion, with the original's exceptions. */
  private static String original(String s, Locale locale, boolean upper) {
    return upper ? s.toUpperCase(locale) : s.toLowerCase(locale);
  }

  /** Locales where String case conversion has no special cases (see String.toLowerCase). */
  private static boolean simpleCasing(Locale locale) {
    String language = locale.getLanguage();
    return !("tr".equals(language) || "az".equals(language) || "lt".equals(language));
  }

  /** Whether every char of {@code s} maps to exactly one char, independent of context. */
  private static boolean mapsOneToOne(String s, boolean upper) {
    int limit = upper ? 0x7F : 0xFF;
    for (int i = 0, length = s.length(); i < length; i++) {
      if (s.charAt(i) > limit) {
        return false;
      }
    }
    return true;
  }

  /** Whether the mapped chars of {@code s} at {@code from} equal {@code other}'s chars. */
  private static boolean regionMatchesMapped(
      String s, int from, String other, int otherFrom, int count, boolean upper) {
    for (int i = 0; i < count; i++) {
      char c = s.charAt(from + i);
      char mapped = upper ? toUpperAscii(c) : Character.toLowerCase(c);
      if (mapped != other.charAt(otherFrom + i)) {
        return false;
      }
    }
    return true;
  }

  private static char toUpperAscii(char c) {
    return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
  }

  // ---- s.substring(...) then startsWith/endsWith/equals

  public static boolean substringStartsWith(String s, int begin, String p) {
    if (null != s && null != p && begin >= 0 && begin <= s.length()) {
      return s.startsWith(p, begin);
    }
    return s.substring(begin).startsWith(p);
  }

  public static boolean substringEndsWith(String s, int begin, String p) {
    if (null != s && null != p && begin >= 0 && begin <= s.length()) {
      return s.length() - begin >= p.length() && s.endsWith(p);
    }
    return s.substring(begin).endsWith(p);
  }

  public static boolean substringEquals(String s, int begin, Object t) {
    if (null != s && begin >= 0 && begin <= s.length()) {
      return t instanceof String && regionEquals(s, begin, s.length(), (String) t);
    }
    return s.substring(begin).equals(t);
  }

  public static boolean substringStartsWith(String s, int begin, int end, String p) {
    if (null != s && null != p && begin >= 0 && end <= s.length() && begin <= end) {
      return end - begin >= p.length() && s.startsWith(p, begin);
    }
    return s.substring(begin, end).startsWith(p);
  }

  public static boolean substringEndsWith(String s, int begin, int end, String p) {
    if (null != s && null != p && begin >= 0 && end <= s.length() && begin <= end) {
      int prefix = p.length();
      return end - begin >= prefix && s.startsWith(p, end - prefix);
    }
    return s.substring(begin, end).endsWith(p);
  }

  public static boolean substringEquals(String s, int begin, int end, Object t) {
    if (null != s && begin >= 0 && end <= s.length() && begin <= end) {
      return t instanceof String && regionEquals(s, begin, end, (String) t);
    }
    return s.substring(begin, end).equals(t);
  }

  public static boolean equalsSubstring(String receiver, String s, int begin) {
    if (null != receiver && null != s && begin >= 0 && begin <= s.length()) {
      return regionEquals(s, begin, s.length(), receiver);
    }
    String substring = s.substring(begin);
    return receiver.equals(substring);
  }

  public static boolean equalsSubstring(String receiver, String s, int begin, int end) {
    if (null != receiver && null != s && begin >= 0 && end <= s.length() && begin <= end) {
      return regionEquals(s, begin, end, receiver);
    }
    String substring = s.substring(begin, end);
    return receiver.equals(substring);
  }

  private static boolean regionEquals(String s, int begin, int end, String other) {
    int length = end - begin;
    return other.length() == length && s.regionMatches(begin, other, 0, length);
  }

  // ---- s.trim() then isEmpty/length

  public static boolean trimIsEmpty(String s) {
    for (int i = 0, length = s.length(); i < length; i++) { // null s throws like trim()
      if (s.charAt(i) > ' ') {
        return false;
      }
    }
    return true;
  }

  public static int trimLength(String s) {
    int end = s.length(); // null s throws like trim()
    int start = 0;
    while (start < end && s.charAt(start) <= ' ') {
      start++;
    }
    while (end > start && s.charAt(end - 1) <= ' ') {
      end--;
    }
    return end - start;
  }
}

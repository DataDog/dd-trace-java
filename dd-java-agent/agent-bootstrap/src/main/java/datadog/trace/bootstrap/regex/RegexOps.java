package datadog.trace.bootstrap.regex;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.regex.Pattern;

/** Drop-in replacements for JDK regex entry points, backed by {@link RegexCache}. */
@SuppressForbidden
public final class RegexOps {

  private RegexOps() {}

  public static Pattern compile(String regex) {
    return RegexCache.get(regex);
  }

  public static Pattern compile(String regex, int flags) {
    return RegexCache.get(regex, flags);
  }

  public static boolean patternMatches(String regex, CharSequence input) {
    return RegexCache.get(regex).matcher(input).matches();
  }

  public static boolean matches(String self, String regex) {
    requireReceiver(self);
    return RegexCache.get(regex).matcher(self).matches();
  }

  public static String replaceAll(String self, String regex, String replacement) {
    requireReceiver(self);
    return RegexCache.get(regex).matcher(self).replaceAll(replacement);
  }

  public static String replaceFirst(String self, String regex, String replacement) {
    requireReceiver(self);
    return RegexCache.get(regex).matcher(self).replaceFirst(replacement);
  }

  public static String[] split(String self, String regex) {
    return split(self, regex, 0);
  }

  public static String[] split(String self, String regex, int limit) {
    requireReceiver(self);
    if (regex == null || isSplitFastpath(regex)) {
      return self.split(regex, limit);
    }
    return RegexCache.get(regex).split(self, limit);
  }

  static boolean isSplitFastpath(String regex) {
    char ch = 0;
    return ((regex.length() == 1 && ".$|()[{^?*+\\".indexOf(ch = regex.charAt(0)) == -1)
            || (regex.length() == 2
                && regex.charAt(0) == '\\'
                && (((ch = regex.charAt(1)) - '0') | ('9' - ch)) < 0
                && ((ch - 'a') | ('z' - ch)) < 0
                && ((ch - 'A') | ('Z' - ch)) < 0))
        && (ch < Character.MIN_HIGH_SURROGATE || ch > Character.MAX_LOW_SURROGATE);
  }

  private static void requireReceiver(String self) {
    if (self == null) {
      throw new NullPointerException();
    }
  }
}

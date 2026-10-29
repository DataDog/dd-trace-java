package datadog.trace.bootstrap.regex;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.regex.Pattern;

/**
 * Drop-in replacements for JDK regex entry points, backed by {@link RegexCache}. Each method must
 * be observably identical to the JDK method it replaces (results, exception types and messages).
 *
 * <p>CONTRACT STUB: delegates straight to the JDK. Stream A implements the cached versions.
 * Signatures are frozen.
 */
@SuppressForbidden
public final class RegexOps {

  private RegexOps() {}

  public static Pattern compile(String regex) {
    return Pattern.compile(regex);
  }

  public static Pattern compile(String regex, int flags) {
    return Pattern.compile(regex, flags);
  }

  public static boolean patternMatches(String regex, CharSequence input) {
    return Pattern.matches(regex, input);
  }

  public static boolean matches(String self, String regex) {
    return self.matches(regex);
  }

  public static String replaceAll(String self, String regex, String replacement) {
    return self.replaceAll(regex, replacement);
  }

  public static String replaceFirst(String self, String regex, String replacement) {
    return self.replaceFirst(regex, replacement);
  }

  public static String[] split(String self, String regex) {
    return self.split(regex);
  }

  public static String[] split(String self, String regex, int limit) {
    return self.split(regex, limit);
  }
}

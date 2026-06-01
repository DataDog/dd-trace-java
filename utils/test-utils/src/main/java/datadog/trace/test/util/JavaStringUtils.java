package datadog.trace.test.util;

import java.util.Locale;

/**
 * Java port of {@link StringUtils}. Will replace {@code StringUtils.groovy} once all Groovy callers
 * have been migrated to Java.
 */
public final class JavaStringUtils {

  private JavaStringUtils() {}

  public static String padHexLower(String hex, int size) {
    String lower = hex.toLowerCase(Locale.ROOT);
    int diff = size - lower.length();
    if (size <= 0 || diff <= 0) {
      return lower;
    }
    StringBuilder sb = new StringBuilder(size);
    for (int i = 0; i < diff; i++) {
      sb.append('0');
    }
    sb.append(lower);
    return sb.toString();
  }

  public static String trimHex(String hex) {
    int length = hex.length();
    int firstNonZero = 0;
    while (firstNonZero < length && hex.charAt(firstNonZero) == '0') {
      firstNonZero++;
    }
    if (firstNonZero == length) {
      return "0";
    }
    return hex.substring(firstNonZero, length);
  }
}

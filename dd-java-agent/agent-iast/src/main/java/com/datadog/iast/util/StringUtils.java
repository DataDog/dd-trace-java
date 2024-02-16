package com.datadog.iast.util;

import javax.annotation.Nonnull;

public abstract class StringUtils {

  private StringUtils() {}

  /**
   * Checks if the string ends with the selected suffix ignoring case. Note that this method does
   * not take locale into account.
   */
  public static boolean endsWithIgnoreCase(
      @Nonnull final String value, @Nonnull final String suffix) {
    if (value.length() < suffix.length()) {
      return false;
    }
    if (suffix.isEmpty()) {
      return true;
    }
    final int offset = value.length() - suffix.length();
    return value.regionMatches(true, offset, suffix, 0, suffix.length());
  }

  /**
   * Performs a substring of the selected string taking care of leading and trailing whitespaces.
   */
  @Nonnull
  public static String substringTrim(@Nonnull final String value, int start, int end) {
    if (start >= end) {
      return "";
    }
    while (start < end && Character.isWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return start >= end ? "" : value.substring(start, end);
  }
}

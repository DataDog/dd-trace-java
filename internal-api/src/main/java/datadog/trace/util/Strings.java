package datadog.trace.util;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

public final class Strings {

  private static final byte[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  /** com.foo.Bar -> com/foo/Bar.class */
  public static String getResourceName(final String className) {
    if (!className.endsWith(".class")) {
      return className.replace('.', '/') + ".class";
    } else {
      return className;
    }
  }

  /** com/foo/Bar.class -> com.foo.Bar */
  public static String getClassName(final String resourceName) {
    if (resourceName.endsWith(".class")) {
      return resourceName.substring(0, resourceName.length() - 6).replace('/', '.');
    }
    return resourceName.replace('/', '.');
  }

  /** com.foo.Bar -> com/foo/Bar */
  public static String getInternalName(final String className) {
    return className.replace('.', '/');
  }

  /** com.foo.Bar -> com.foo */
  public static String getPackageName(final String className) {
    int lastDot = className.lastIndexOf('.');
    return lastDot < 0 ? "" : className.substring(0, lastDot);
  }

  // reimplementation of string functions without regex
  public static String replace(String str, String delimiter, String replacement) {
    StringBuilder sb = new StringBuilder(str);
    int matchIndex, curIndex = 0;
    while ((matchIndex = sb.indexOf(delimiter, curIndex)) != -1) {
      sb.replace(matchIndex, matchIndex + delimiter.length(), replacement);
      curIndex = matchIndex + replacement.length();
    }
    return sb.toString();
  }

  public static String replaceFirst(String str, String delimiter, String replacement) {
    StringBuilder sb = new StringBuilder(str);
    int i = sb.indexOf(delimiter);
    if (i != -1) {
      sb.replace(i, i + delimiter.length(), replacement);
    }
    return sb.toString();
  }

  public static String sha256(String input) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    StringBuilder hexString = new StringBuilder(2 * hash.length);
    for (byte b : hash) {
      String hex = Integer.toHexString(0xFF & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }

  public static String truncate(String input, int limit) {
    return (String) truncate((CharSequence) input, limit);
  }

  public static CharSequence truncate(CharSequence input, int limit) {
    if (input == null || input.length() <= limit) {
      return input;
    }
    return input.subSequence(0, limit);
  }

  /**
   * Checks that a string is not blank, i.e. contains at least one character that is not a
   * whitespace
   *
   * @param s The string to be checked
   * @return {@code true} if string is not blank, {@code false} otherwise (string is {@code null},
   *     empty, or contains only whitespace characters)
   */
  public static boolean isNotBlank(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }

    // the code below traverses string characters one by one
    // and checks if there is any character that is not a whitespace (space, tab, newline, etc);
    final int length = s.length();
    for (int offset = 0; offset < length; ) {
      // codepoints are used instead of chars, to properly handle non-unicode symbols
      final int codepoint = s.codePointAt(offset);
      if (!Character.isWhitespace(codepoint)) {
        return true;
      }
      offset += Character.charCount(codepoint);
    }

    return false;
  }

  /**
   * Checks that a string is blank, i.e. doest not contain characters or is null
   *
   * @param s The string to be checked
   * @return {@code true} if string is blank (string is {@code null}, empty, or contains only
   *     whitespace characters), {@code false} otherwise
   */
  public static boolean isBlank(String s) {
    return !isNotBlank(s);
  }

  /**
   * Generates a random string of the given length from lowercase characters a-z
   *
   * @param length length of the string
   * @return random string containing lowercase latin characters
   */
  public static String random(int length) {
    char[] c = new char[length];
    for (int i = 0; i < length; i++) {
      c[i] = (char) ('a' + ThreadLocalRandom.current().nextInt(26));
    }
    return new String(c);
  }

  public static String toHexString(byte[] value) {
    if (value == null) {
      return null;
    }
    byte[] bytes = new byte[value.length * 2];
    for (int i = 0; i < value.length; i++) {
      byte v = value[i];
      bytes[i * 2] = HEX_DIGITS[(v & 0xF0) >>> 4];
      bytes[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
    }
    return new String(bytes, US_ASCII);
  }

  public static String[] concat(String[] arr, String... extra) {
    if (arr.length == 0) return extra;
    if (extra.length == 0) return arr;
    String[] result = new String[arr.length + extra.length];
    System.arraycopy(arr, 0, result, 0, arr.length);
    System.arraycopy(extra, 0, result, arr.length, extra.length);
    return result;
  }

  /**
   * @return first non-blank string out of the two, {@code null} if both are blank
   */
  @Nullable
  public static String coalesce(@Nullable final String first, @Nullable final String second) {
    if (isNotBlank(first)) {
      return first;
    } else if (isNotBlank(second)) {
      return second;
    } else {
      return null;
    }
  }
}

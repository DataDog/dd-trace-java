package datadog.trace.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;

public final class Strings {

  public static String escapeToJson(String string) {
    if (string == null || string.isEmpty()) {
      return "";
    }

    final StringBuilder sb = new StringBuilder();
    int sz = string.length();
    for (int i = 0; i < sz; ++i) {
      char ch = string.charAt(i);
      if (ch > 4095) {
        sb.append("\\u").append(hex(ch));
      } else if (ch > 255) {
        sb.append("\\u0").append(hex(ch));
      } else if (ch > 127) {
        sb.append("\\u00").append(hex(ch));
      } else if (ch < ' ') {
        switch (ch) {
          case '\b':
            sb.append((char) 92).append((char) 98);
            break;
          case '\t':
            sb.append((char) 92).append((char) 116);
            break;
          case '\n':
            sb.append((char) 92).append((char) 110);
            break;
          case '\u000b':
          default:
            if (ch > 15) {
              sb.append("\\u00").append(hex(ch));
            } else {
              sb.append("\\u000").append(hex(ch));
            }
            break;
          case '\f':
            sb.append((char) 92).append((char) 102);
            break;
          case '\r':
            sb.append((char) 92).append((char) 114);
            break;
        }
      } else {
        switch (ch) {
          case '"':
            sb.append((char) 92).append((char) 34);
            break;
          case '\'':
            sb.append((char) 92).append((char) 39);
            break;
          case '/':
            sb.append((char) 92).append((char) 47);
            break;
          case '\\':
            sb.append((char) 92).append((char) 92);
            break;
          default:
            sb.append(ch);
        }
      }
    }

    return sb.toString();
  }

  public static String toEnvVar(String string) {
    return string.replace('.', '_').replace('-', '_').toUpperCase();
  }

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

  public static String join(CharSequence joiner, Iterable<? extends CharSequence> strings) {
    if (strings == null) {
      return "";
    }

    Iterator<? extends CharSequence> it = strings.iterator();
    // no elements
    if (!it.hasNext()) {
      return "";
    }

    // first element
    CharSequence first = it.next();
    if (!it.hasNext()) {
      return first.toString();
    }

    // remaining elements with joiner
    StringBuilder sb = new StringBuilder(first);
    while (it.hasNext()) {
      sb.append(joiner).append(it.next());
    }
    return sb.toString();
  }

  public static String join(CharSequence joiner, CharSequence... strings) {
    int len = strings.length;
    if (len > 0) {
      if (len == 1) {
        return strings[0].toString();
      }
      StringBuilder sb = new StringBuilder(strings[0]);
      for (int i = 1; i < len; ++i) {
        sb.append(joiner).append(strings[i]);
      }
      return sb.toString();
    }
    return "";
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

  /**
   * Converts the property name, e.g. 'service.name' into a public environment variable name, e.g.
   * `DD_SERVICE_NAME`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing environment variable name
   */
  @Nonnull
  public static String propertyNameToEnvironmentVariableName(final String setting) {
    return "DD_" + toEnvVar(setting);
  }

  /**
   * Converts the system property name, e.g. 'dd.service.name' into a public environment variable
   * name, e.g. `DD_SERVICE_NAME`.
   *
   * @param setting The system property name, e.g. `dd.service.name`
   * @return The public facing environment variable name
   */
  @Nonnull
  public static String systemPropertyNameToEnvironmentVariableName(final String setting) {
    return setting.replace('.', '_').replace('-', '_').toUpperCase();
  }

  /**
   * Converts the property name, e.g. 'service.name' into a public system property name, e.g.
   * `dd.service.name`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing system property name
   */
  @Nonnull
  public static String propertyNameToSystemPropertyName(final String setting) {
    return "dd." + setting;
  }

  @Nonnull
  public static String normalizedHeaderTag(String str) {
    if (str.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder(str.length());
    int firstNonWhiteSpace = -1;
    int lastNonWhitespace = -1;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (Character.isWhitespace(c)) {
        builder.append('_');
      } else {
        firstNonWhiteSpace = firstNonWhiteSpace == -1 ? i : firstNonWhiteSpace;
        lastNonWhitespace = i;
        if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/') {
          builder.append(Character.toLowerCase(c));
        } else {
          builder.append('_');
        }
      }
    }
    if (firstNonWhiteSpace == -1) {
      return "";
    } else {
      str = builder.substring(firstNonWhiteSpace, lastNonWhitespace + 1);
      return str;
    }
  }

  @Nonnull
  public static String trim(final String string) {
    return null == string ? "" : string.trim();
  }

  private static String hex(char ch) {
    return Integer.toHexString(ch).toUpperCase(Locale.ENGLISH);
  }

  public static String sha256(String input) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    StringBuilder hexString = new StringBuilder(2 * hash.length);
    for (int i = 0; i < hash.length; i++) {
      String hex = Integer.toHexString(0xFF & hash[i]);
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

  public static String toJson(final Map<String, String> map) {
    return toJson(map, false);
  }

  public static String toJson(final Map<String, String> map, boolean valuesAreJson) {
    if (map == null || map.isEmpty()) {
      return "{}";
    }
    final StringBuilder sb = new StringBuilder("{");
    final Iterator<Entry<String, String>> entriesIter = map.entrySet().iterator();
    while (entriesIter.hasNext()) {
      final Entry<String, String> entry = entriesIter.next();

      sb.append("\"").append(escapeToJson(entry.getKey())).append("\":");

      if (valuesAreJson) {
        sb.append(entry.getValue());
      } else {
        sb.append("\"").append(escapeToJson(entry.getValue())).append("\"");
      }

      if (entriesIter.hasNext()) {
        sb.append(",");
      }
    }
    sb.append("}");
    return sb.toString();
  }

  public static String toJson(final Iterable<String> items) {
    if (items == null) {
      return "[]";
    }
    StringBuilder json = new StringBuilder("[");
    Iterator<String> it = items.iterator();
    while (it.hasNext()) {
      String item = it.next();
      json.append('"').append(escapeToJson(item)).append('"');
      if (it.hasNext()) {
        json.append(",");
      }
    }
    json.append("]");
    return json.toString();
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
}

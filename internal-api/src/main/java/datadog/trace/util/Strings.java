package datadog.trace.util;

public final class Strings {

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
  public static String getInternalName(final String resourceName) {
    return resourceName.replace('.', '/');
  }

  /**
   * Convert class name to a format that can be used as part of inner class name by replacing all
   * '.'s with '$'s.
   *
   * @param className class named to be converted
   * @return convertd name
   */
  public static String getInnerClassName(final String className) {
    return className.replace('.', '$');
  }

  public static String join(CharSequence joiner, Iterable<? extends CharSequence> strings) {
    StringBuilder sb = new StringBuilder();
    for (CharSequence string : strings) {
      sb.append(string).append(joiner);
    }
    // truncate to remove the last joiner
    if (sb.length() > 0) {
      sb.setLength(sb.length() - joiner.length());
    }
    return sb.toString();
  }

  public static String join(CharSequence joiner, CharSequence... strings) {
    if (strings.length > 0) {
      StringBuilder sb = new StringBuilder();
      sb.append(strings[0]);
      for (int i = 1; i < strings.length; ++i) {
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
    if (i != -1) sb.replace(i, i + delimiter.length(), replacement);
    return sb.toString();
  }
}

package datadog.trace.api.normalize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringTokenizer;

/**
 * PathMatcher implementation for Ant-style path patterns. Examples are provided below.
 *
 * <p>Part of this mapping code has been kindly borrowed from <a
 * href="http://shiro.apache.org">Apache Shiro</a> and simplified for our use case. <a
 * href="https://github.com/apache/shiro/blob/shiro-root-1.7.1/core/src/main/java/org/apache/shiro/util/AntPathMatcher.java">AntPathMatcher.java</a>
 *
 * <p>The mapping matches URLs using the following rules:<br>
 *
 * <ul>
 *   <li>? matches one character
 *   <li>* matches zero or more characters
 *   <li>** matches zero or more 'directories' in a path
 * </ul>
 *
 * <p>Some examples:<br>
 *
 * <ul>
 *   <li><code>com/t?st.jsp</code> - matches <code>com/test.jsp</code> but also <code>com/tast.jsp
 *       </code> or <code>com/txst.jsp</code>
 *   <li><code>com/*.jsp</code> - matches all <code>.jsp</code> files in the <code>com</code>
 *       directory
 *   <li><code>com/&#42;&#42;/test.jsp</code> - matches all <code>test.jsp</code> files underneath
 *       the <code>com</code> path
 *   <li><code>com/datadoghq/dd-trace-java/&#42;&#42;/*.jsp</code> - matches all <code>.jsp</code>
 *       files underneath the <code>com/datadoghq/dd-trace-java</code> path
 *   <li><code>com/&#42;&#42;/servlet/bla.jsp</code> - matches <code>
 *       com/datadoghq/dd-trace-java/servlet/bla.jsp</code> but also <code>
 *       com/datadoghq/dd-trace-java/testing/servlet/bla.jsp</code> and <code>com/servlet/bla.jsp
 *       </code>
 * </ul>
 */
final class AntPathMatcher {

  AntPathMatcher() {}

  /**
   * Checks if {@code path} is a pattern (i.e. contains a '*', or '?'). For example the {@code
   * /foo/**} would return {@code true}, while {@code /bar/} would return {@code false}.
   *
   * @param path the string to check
   * @return this method returns {@code true} if {@code path} contains a '*' or '?', otherwise,
   *     {@code false}
   */
  public boolean isPattern(String path) {
    return (path.indexOf('*') != -1 || path.indexOf('?') != -1);
  }

  /**
   * match the given <code>path</code> against the given <code>pattern</code>.
   *
   * @param pattern the pattern to match against
   * @param path the path String to test
   * @return <code>true</code> if the supplied <code>path</code> matched, <code>false</code> if it
   *     didn't
   */
  public boolean match(String pattern, String path) {
    String pathSeparator = "/";
    if (path == null || path.startsWith(pathSeparator) != pattern.startsWith(pathSeparator)) {
      return false;
    }

    String[] pattDirs = tokenizeToStringArray(pattern, pathSeparator);
    String[] pathDirs = tokenizeToStringArray(path, pathSeparator);

    int pattIdxStart = 0;
    int pattIdxEnd = pattDirs.length - 1;
    int pathIdxStart = 0;
    int pathIdxEnd = pathDirs.length - 1;

    // Match all elements up to the first **
    while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
      String patDir = pattDirs[pattIdxStart];
      if ("**".equals(patDir)) {
        break;
      }
      if (!matchStrings(patDir, pathDirs[pathIdxStart])) {
        return false;
      }
      pattIdxStart++;
      pathIdxStart++;
    }

    if (pathIdxStart > pathIdxEnd) {
      // Path is exhausted, only match if rest of pattern is * or **'s
      if (pattIdxStart > pattIdxEnd) {
        return (pattern.endsWith(pathSeparator) == path.endsWith(pathSeparator));
      }
      if (pattIdxStart == pattIdxEnd
          && pattDirs[pattIdxStart].equals("*")
          && path.endsWith(pathSeparator)) {
        return true;
      }
      for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
        if (!pattDirs[i].equals("**")) {
          return false;
        }
      }
      return true;
    } else if (pattIdxStart > pattIdxEnd) {
      // String not exhausted, but pattern is. Failure.
      return false;
    }

    // up to last '**'
    while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
      String patDir = pattDirs[pattIdxEnd];
      if (patDir.equals("**")) {
        break;
      }
      if (!matchStrings(patDir, pathDirs[pathIdxEnd])) {
        return false;
      }
      pattIdxEnd--;
      pathIdxEnd--;
    }
    if (pathIdxStart > pathIdxEnd) {
      // String is exhausted
      for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
        if (!pattDirs[i].equals("**")) {
          return false;
        }
      }
      return true;
    }

    while (pattIdxStart != pattIdxEnd && pathIdxStart <= pathIdxEnd) {
      int patIdxTmp = -1;
      for (int i = pattIdxStart + 1; i <= pattIdxEnd; i++) {
        if (pattDirs[i].equals("**")) {
          patIdxTmp = i;
          break;
        }
      }
      if (patIdxTmp == pattIdxStart + 1) {
        // '**/**' situation, so skip one
        pattIdxStart++;
        continue;
      }
      // Find the pattern between padIdxStart & padIdxTmp in str between
      // strIdxStart & strIdxEnd
      int patLength = (patIdxTmp - pattIdxStart - 1);
      int strLength = (pathIdxEnd - pathIdxStart + 1);
      int foundIdx = -1;

      strLoop:
      for (int i = 0; i <= strLength - patLength; i++) {
        for (int j = 0; j < patLength; j++) {
          String subPat = pattDirs[pattIdxStart + j + 1];
          String subStr = pathDirs[pathIdxStart + i + j];
          if (!matchStrings(subPat, subStr)) {
            continue strLoop;
          }
        }
        foundIdx = pathIdxStart + i;
        break;
      }

      if (foundIdx == -1) {
        return false;
      }

      pattIdxStart = patIdxTmp;
      pathIdxStart = foundIdx + patLength;
    }

    for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
      if (!pattDirs[i].equals("**")) {
        return false;
      }
    }

    return true;
  }

  /**
   * Tests whether or not a string matches against a pattern. The pattern may contain two special
   * characters:<br>
   * '*' means zero or more characters<br>
   * '?' means one and only one character
   *
   * @param pattern pattern to match against. Must not be <code>null</code>.
   * @param str string which must be matched against the pattern. Must not be <code>null</code>.
   * @return <code>true</code> if the string matches against the pattern, or <code>false</code>
   *     otherwise.
   */
  private boolean matchStrings(String pattern, String str) {
    char[] patArr = pattern.toCharArray();
    char[] strArr = str.toCharArray();
    int patIdxStart = 0;
    int patIdxEnd = patArr.length - 1;
    int strIdxStart = 0;
    int strIdxEnd = strArr.length - 1;
    char ch;

    boolean containsStar = false;
    for (char aPatArr : patArr) {
      if (aPatArr == '*') {
        containsStar = true;
        break;
      }
    }

    if (!containsStar) {
      // No '*'s, so we make a shortcut
      if (patIdxEnd != strIdxEnd) {
        return false; // Pattern and string do not have the same size
      }
      for (int i = 0; i <= patIdxEnd; i++) {
        ch = patArr[i];
        if (ch != '?') {
          if (ch != strArr[i]) {
            return false; // Character mismatch
          }
        }
      }
      return true; // String matches against pattern
    }

    if (patIdxEnd == 0) {
      return true; // Pattern contains only '*', which matches anything
    }

    // Process characters before first star
    while ((ch = patArr[patIdxStart]) != '*' && strIdxStart <= strIdxEnd) {
      if (ch != '?') {
        if (ch != strArr[strIdxStart]) {
          return false; // Character mismatch
        }
      }
      patIdxStart++;
      strIdxStart++;
    }
    if (strIdxStart > strIdxEnd) {
      // All characters in the string are used. Check if only '*'s are
      // left in the pattern. If so, we succeeded. Otherwise failure.
      for (int i = patIdxStart; i <= patIdxEnd; i++) {
        if (patArr[i] != '*') {
          return false;
        }
      }
      return true;
    }

    // Process characters after last star
    while ((ch = patArr[patIdxEnd]) != '*' && strIdxStart <= strIdxEnd) {
      if (ch != '?') {
        if (ch != strArr[strIdxEnd]) {
          return false; // Character mismatch
        }
      }
      patIdxEnd--;
      strIdxEnd--;
    }
    if (strIdxStart > strIdxEnd) {
      // All characters in the string are used. Check if only '*'s are
      // left in the pattern. If so, we succeeded. Otherwise failure.
      for (int i = patIdxStart; i <= patIdxEnd; i++) {
        if (patArr[i] != '*') {
          return false;
        }
      }
      return true;
    }

    // process pattern between stars. padIdxStart and patIdxEnd point
    // always to a '*'.
    while (patIdxStart != patIdxEnd && strIdxStart <= strIdxEnd) {
      int patIdxTmp = -1;
      for (int i = patIdxStart + 1; i <= patIdxEnd; i++) {
        if (patArr[i] == '*') {
          patIdxTmp = i;
          break;
        }
      }
      if (patIdxTmp == patIdxStart + 1) {
        // Two stars next to each other, skip the first one.
        patIdxStart++;
        continue;
      }
      // Find the pattern between padIdxStart & padIdxTmp in str between
      // strIdxStart & strIdxEnd
      int patLength = (patIdxTmp - patIdxStart - 1);
      int strLength = (strIdxEnd - strIdxStart + 1);
      int foundIdx = -1;
      strLoop:
      for (int i = 0; i <= strLength - patLength; i++) {
        for (int j = 0; j < patLength; j++) {
          ch = patArr[patIdxStart + j + 1];
          if (ch != '?') {
            if (ch != strArr[strIdxStart + i + j]) {
              continue strLoop;
            }
          }
        }

        foundIdx = strIdxStart + i;
        break;
      }

      if (foundIdx == -1) {
        return false;
      }

      patIdxStart = patIdxTmp;
      strIdxStart = foundIdx + patLength;
    }

    // All characters in the string are used. Check if only '*'s are left
    // in the pattern. If so, we succeeded. Otherwise failure.
    for (int i = patIdxStart; i <= patIdxEnd; i++) {
      if (patArr[i] != '*') {
        return false;
      }
    }

    return true;
  }

  private static final String[] EMPTY_STRING_ARRAY = {};

  private static String[] tokenizeToStringArray(String str, String delimiters) {

    if (str == null) {
      return EMPTY_STRING_ARRAY;
    }

    StringTokenizer st = new StringTokenizer(str, delimiters);
    List<String> tokens = new ArrayList<>();
    while (st.hasMoreTokens()) {
      String token = st.nextToken();
      if (token.length() > 0) {
        tokens.add(token);
      }
    }
    return toStringArray(tokens);
  }

  private static String[] toStringArray(Collection<String> collection) {
    return ((collection != null && !collection.isEmpty())
        ? collection.toArray(EMPTY_STRING_ARRAY)
        : EMPTY_STRING_ARRAY);
  }
}

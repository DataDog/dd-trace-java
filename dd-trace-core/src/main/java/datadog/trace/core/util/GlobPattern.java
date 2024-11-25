package datadog.trace.core.util;

import java.util.regex.Pattern;

public final class GlobPattern {

  public static Pattern globToRegexPattern(String globPattern) {
    String regex = globToRegex(globPattern);
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
  }

  private static String globToRegex(String globPattern) {
    StringBuilder sb = new StringBuilder(64);
    sb.append('^');
    for (int i = 0; i < globPattern.length(); i++) {
      char ch = globPattern.charAt(i);
      switch (ch) {
        case '?':
          sb.append('.');
          break;
        case '*':
          sb.append(".*");
          break;
        case '^':
        case '$':
        case '|':
        case '.':
        case '\\':
        case '(':
        case ')':
        case '[':
        case ']':
        case '{':
        case '}':
          sb.append('\\').append(ch);
          break;
        default:
          sb.append(ch);
          break;
      }
    }
    sb.append('$');
    return sb.toString();
  }

  private GlobPattern() {}
}

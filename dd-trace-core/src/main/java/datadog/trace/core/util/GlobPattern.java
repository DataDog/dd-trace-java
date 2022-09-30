package datadog.trace.core.util;

public final class GlobPattern {

  public static String globToRegex(String globPattern) {
    StringBuilder sb = new StringBuilder(64);
    sb.append('^');
    for (int i = 0; i < globPattern.length(); i++) {
      char ch = globPattern.charAt(i);
      switch (ch) {
        case '?':
          sb.append('.');
          break;
        case '*':
          sb.append(".+");
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
          sb.append("\\").append(ch);
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

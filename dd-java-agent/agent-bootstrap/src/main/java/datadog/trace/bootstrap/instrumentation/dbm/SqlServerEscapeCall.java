package datadog.trace.bootstrap.instrumentation.dbm;

/** Locates an argument-comment position without interpreting SQL literals as call delimiters. */
final class SqlServerEscapeCall {
  static final int UNSUPPORTED = -1;
  static final int ALREADY_INJECTED = -2;

  private SqlServerEscapeCall() {}

  /** Returns the closing argument parenthesis, or the closing brace for a parameterless call. */
  static int commentPosition(String sql) {
    int index = skipWhitespace(sql, 0);
    if (index == sql.length() || sql.charAt(index++) != '{') {
      return UNSUPPORTED;
    }
    index = skipWhitespace(sql, index);
    if (index < sql.length() && sql.charAt(index) == '?') {
      index = skipWhitespace(sql, index + 1);
      if (index == sql.length() || sql.charAt(index++) != '=') {
        return UNSUPPORTED;
      }
      index = skipWhitespace(sql, index);
    }
    if (!sql.regionMatches(true, index, "call", 0, 4)
        || index + 4 == sql.length()
        || !Character.isWhitespace(sql.charAt(index + 4))) {
      return UNSUPPORTED;
    }
    index = skipWhitespace(sql, index + 4);
    int nameStart = index;
    while (index < sql.length()) {
      char c = sql.charAt(index);
      if (c == '[' || c == '"') {
        index = skipQuoted(sql, index, c == '[' ? ']' : '"');
        if (index == UNSUPPORTED) {
          return UNSUPPORTED;
        }
      } else if (Character.isLetterOrDigit(c)
          || c == '.'
          || c == '_'
          || c == '@'
          || c == '#'
          || c == '$') {
        index++;
      } else {
        break;
      }
    }
    if (index == nameStart) {
      return UNSUPPORTED;
    }
    index = skipWhitespace(sql, index);
    if (index == sql.length()) {
      return UNSUPPORTED;
    }
    if (sql.charAt(index) == '}') {
      return index;
    }
    if (sql.charAt(index++) != '(') {
      return UNSUPPORTED;
    }

    int depth = 1;
    boolean injected = false;
    while (index < sql.length()) {
      char c = sql.charAt(index);
      if (c == '\'' || c == '"' || c == '[') {
        index = skipQuoted(sql, index, c == '[' ? ']' : c);
        if (index == UNSUPPORTED) {
          return UNSUPPORTED;
        }
      } else if (c == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
        int end = skipBlockComment(sql, index);
        if (end == UNSUPPORTED) {
          return UNSUPPORTED;
        }
        injected |= SharedDBCommenter.containsTraceComment(sql, index + 2, end - 2);
        index = end;
      } else if (c == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
        index += 2;
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
          index++;
        }
      } else if (c == '(') {
        depth++;
        index++;
      } else if (c == ')') {
        if (--depth == 0) {
          int brace = skipWhitespace(sql, index + 1);
          if (brace == sql.length() || sql.charAt(brace) != '}') {
            return UNSUPPORTED;
          }
          return injected ? ALREADY_INJECTED : index;
        }
        index++;
      } else {
        index++;
      }
    }
    return UNSUPPORTED;
  }

  private static int skipWhitespace(String sql, int index) {
    while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
      index++;
    }
    return index;
  }

  private static int skipQuoted(String sql, int index, char close) {
    for (int i = index + 1; i < sql.length(); i++) {
      if (sql.charAt(i) == close) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == close) {
          i++;
        } else {
          return i + 1;
        }
      }
    }
    return UNSUPPORTED;
  }

  private static int skipBlockComment(String sql, int index) {
    int depth = 1;
    for (int i = index + 2; i + 1 < sql.length(); i++) {
      if (sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
        depth++;
        i++;
      } else if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
        if (--depth == 0) {
          return i + 2;
        }
        i++;
      }
    }
    return UNSUPPORTED;
  }
}

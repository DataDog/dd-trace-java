package datadog.trace.bootstrap.instrumentation.log;

/** Adds missing MDC converters after the first complete field of a text logging pattern. */
public final class LogPatternInjector {
  public static final int SERVICE = 1;
  public static final int TRACE_ID = 2;
  public static final int SPAN_ID = 4;
  public static final int ALL = SERVICE | TRACE_ID | SPAN_ID;

  private LogPatternInjector() {}

  /** Uses fields discovered by Logback's native parser. */
  public static String injectLogback(String pattern, int presentFields) {
    return inject(pattern, presentFields, true);
  }

  /** MDC syntax capabilities of the supported Log4j releases. */
  public enum Format {
    LOG4J2_LEGACY(false),
    LOG4J2(true);

    private final boolean keyLists;

    Format(boolean keyLists) {
      this.keyLists = keyLists;
    }
  }

  /** Recognizes MDC converters using the syntax supported by the running framework. */
  public static String injectLog4j(String pattern, Format format) {
    if (pattern == null || pattern.isEmpty()) {
      return pattern;
    }
    return inject(pattern, fields(pattern, 0, pattern.length(), format), false);
  }

  private static int field(String key) {
    if ("dd.service".equals(key)) {
      return SERVICE;
    }
    if ("dd.trace_id".equals(key)) {
      return TRACE_ID;
    }
    return "dd.span_id".equals(key) ? SPAN_ID : 0;
  }

  private static int fields(String pattern, int start, int end, Format format) {
    int found = 0;
    for (int i = start; i < end; i++) {
      if (pattern.charAt(i) != '%') {
        continue;
      }
      i++;
      if (i < end && pattern.charAt(i) == '%') {
        continue;
      }
      while (i < end
          && (pattern.charAt(i) == '-'
              || pattern.charAt(i) == '.'
              || Character.isDigit(pattern.charAt(i)))) {
        i++;
      }
      int nameStart = i;
      while (i < end && Character.isLetter(pattern.charAt(i))) {
        i++;
      }
      String name = pattern.substring(nameStart, i);
      boolean mdc = "X".equals(name) || "mdc".equals(name) || "MDC".equals(name);
      boolean firstOption = true;
      if (mdc && (i == end || pattern.charAt(i) != '{')) {
        return ALL;
      }
      while (i < end && pattern.charAt(i) == '{') {
        int close = optionEnd(pattern, i, end);
        if (close < 0) {
          return ALL; // Leave malformed patterns to the framework's validation.
        }
        if (mdc && firstOption) {
          String keys = pattern.substring(i + 1, close);
          if (format.keyLists && keys.indexOf(',') > 0) {
            for (String key : keys.split(",")) {
              found |= field(key.trim());
            }
          } else {
            found |= field(keys);
          }
        } else if (firstOption && isComposite(name)) {
          found |= fields(pattern, i + 1, close, format);
        }
        firstOption = false;
        i = close + 1;
      }
      i--;
    }
    return found;
  }

  private static boolean isComposite(String name) {
    return "style".equals(name)
        || "highlight".equals(name)
        || "replace".equals(name)
        || "notEmpty".equals(name)
        || "varsNotEmpty".equals(name)
        || "variablesNotEmpty".equals(name)
        || "equals".equals(name)
        || "equalsIgnoreCase".equals(name)
        || "maxLen".equals(name)
        || "enc".equals(name)
        || "encode".equals(name);
  }

  private static int optionEnd(String pattern, int start, int end) {
    int depth = 1;
    for (int i = start + 1; i < end; i++) {
      char c = pattern.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isLineBreak(String pattern, int i, boolean logback) {
    return pattern.startsWith("%n", i)
        && (logback || i + 2 == pattern.length() || !Character.isLetter(pattern.charAt(i + 2)));
  }

  private static String inject(String pattern, int presentFields, boolean logback) {
    if (pattern == null || pattern.isEmpty() || presentFields == ALL) {
      return pattern;
    }
    // Preserve the first complete field, including date options and presentation wrappers.
    int insertion = pattern.length();
    int optionsDepth = 0;
    int compositeDepth = 0;
    int bracketDepth = 0;
    char quote = 0;
    boolean fieldStarted = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\' && logback) {
        fieldStarted = true;
        i++;
        continue;
      }
      if (!logback && c == '%' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '%') {
        fieldStarted = true;
        i++;
        continue;
      }
      if (optionsDepth > 0) {
        if (quote != 0) {
          if (c == quote) {
            quote = 0;
          }
        } else if (c == '\'' || c == '"') {
          quote = c;
        } else if (c == '{') {
          optionsDepth++;
        } else if (c == '}') {
          optionsDepth--;
        }
        continue;
      }
      if (c == '{') {
        optionsDepth++;
      } else if (logback && c == '(') {
        compositeDepth++;
      } else if (logback && c == ')' && compositeDepth > 0) {
        compositeDepth--;
      } else if (compositeDepth == 0 && c == '[') {
        bracketDepth++;
      } else if (compositeDepth == 0 && c == ']' && bracketDepth > 0) {
        bracketDepth--;
      }
      if (compositeDepth == 0 && fieldStarted) {
        if (isLineBreak(pattern, i, logback) || c == '\n' || c == '\r') {
          insertion = i;
          break;
        }
        if (bracketDepth == 0 && (Character.isWhitespace(c) || c == '|' || c == ',' || c == ';')) {
          insertion = i;
          // Keep the original separator before the new fields, including delimited logs.
          while (insertion < pattern.length()
              && Character.isWhitespace(pattern.charAt(insertion))
              && pattern.charAt(insertion) != '\n'
              && pattern.charAt(insertion) != '\r') {
            insertion++;
          }
          if (insertion < pattern.length()
              && (pattern.charAt(insertion) == '|'
                  || pattern.charAt(insertion) == ','
                  || pattern.charAt(insertion) == ';')) {
            insertion++;
            while (insertion < pattern.length()
                && Character.isWhitespace(pattern.charAt(insertion))
                && pattern.charAt(insertion) != '\n'
                && pattern.charAt(insertion) != '\r') {
              insertion++;
            }
          }
          break;
        }
      }
      fieldStarted |= !Character.isWhitespace(c);
    }
    StringBuilder result = new StringBuilder();
    result.append(pattern, 0, insertion);
    if (insertion > 0) {
      char previous = pattern.charAt(insertion - 1);
      if (!Character.isWhitespace(previous)
          && previous != '|'
          && previous != ','
          && previous != ';') {
        result.append(' ');
      }
    }
    if ((presentFields & SERVICE) == 0) {
      result.append("dd.service=%X{dd.service} ");
    }
    if ((presentFields & TRACE_ID) == 0) {
      result.append("dd.trace_id=%X{dd.trace_id} ");
    }
    if ((presentFields & SPAN_ID) == 0) {
      result.append("dd.span_id=%X{dd.span_id} ");
    }
    if (insertion == pattern.length()
        || isLineBreak(pattern, insertion, logback)
        || pattern.charAt(insertion) == '\n'
        || pattern.charAt(insertion) == '\r') {
      result.setLength(result.length() - 1);
    }
    return result.append(pattern, insertion, pattern.length()).toString();
  }
}

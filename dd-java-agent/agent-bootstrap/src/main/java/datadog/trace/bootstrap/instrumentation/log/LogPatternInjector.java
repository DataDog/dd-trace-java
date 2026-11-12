package datadog.trace.bootstrap.instrumentation.log;

/** Adds missing MDC converters before the message of a text logging pattern. */
public final class LogPatternInjector {
  public static final int SERVICE = 1;
  public static final int TRACE_ID = 2;
  public static final int SPAN_ID = 4;
  public static final int ENV = 8;
  public static final int VERSION = 16;
  public static final int ALL = SERVICE | TRACE_ID | SPAN_ID | ENV | VERSION;

  private LogPatternInjector() {}

  /** Uses fields discovered by Logback's native parser. */
  public static String injectLogback(String pattern, int presentFields) {
    return inject(pattern, presentFields, true, false);
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
  public static String injectLog4j(String pattern, Format format, boolean supportsEquals) {
    if (pattern == null || pattern.isEmpty()) {
      return pattern;
    }
    return inject(pattern, fields(pattern, 0, pattern.length(), format), false, supportsEquals);
  }

  private static int field(String key) {
    if ("dd.service".equals(key)) {
      return SERVICE;
    }
    if ("dd.trace_id".equals(key)) {
      return TRACE_ID;
    }
    if ("dd.span_id".equals(key)) {
      return SPAN_ID;
    }
    if ("dd.env".equals(key)) {
      return ENV;
    }
    return "dd.version".equals(key) ? VERSION : 0;
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
      boolean mdc =
          "X".equals(name) || "mdc".equals(name) || "MDC".equals(name) || "ddAgentId".equals(name);
      boolean firstOption = true;
      if (mdc && (i == end || pattern.charAt(i) != '{')) {
        return ALL;
      }
      while (i < end && pattern.charAt(i) == '{') {
        int close = optionEnd(pattern, i, end, false);
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
    return "black".equals(name)
        || "blue".equals(name)
        || "cyan".equals(name)
        || "green".equals(name)
        || "magenta".equals(name)
        || "red".equals(name)
        || "white".equals(name)
        || "yellow".equals(name)
        || "maxLength".equals(name)
        || "style".equals(name)
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

  private static int logbackGroupEnd(String pattern, int start, int end) {
    int depth = 1;
    for (int i = start + 1; i < end; i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '{') {
        i = optionEnd(pattern, i, end, true);
        if (i < 0) {
          return -1;
        }
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private static int optionEnd(String pattern, int start, int end, boolean logback) {
    int depth = 1;
    char quote = 0;
    for (int i = start + 1; i < end; i++) {
      char c = pattern.charAt(i);
      if (logback && c == '\\') {
        i++;
      } else if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (logback && (c == '\'' || c == '"')) {
        quote = c;
      } else if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /** Searches pattern content, skipping literal converter options and escaped percent signs. */
  private static int findInsertion(
      String pattern, int start, int end, boolean logback, boolean message, int wrapperStart) {
    boolean prefixSeen = false;
    for (int i = start; i < end; ) {
      char c = pattern.charAt(i);
      if (logback && c == '\\') {
        prefixSeen = true;
        i += 2;
        continue;
      }
      if (c != '%') {
        if (!message && (c == '\n' || c == '\r')) {
          return i;
        }
        prefixSeen = true;
        i++;
        continue;
      }
      int tokenStart = i++;
      if (!logback && i < end && pattern.charAt(i) == '%') {
        prefixSeen = true;
        i++;
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
      if (message && ("m".equals(name) || "msg".equals(name) || "message".equals(name))) {
        return prefixSeen || wrapperStart < 0 ? tokenStart : wrapperStart;
      }
      if (!message && "n".equals(name)) {
        return tokenStart;
      }
      int childEnd = -1;
      if (logback && i < end && pattern.charAt(i) == '(') {
        childEnd = logbackGroupEnd(pattern, i, end);
      } else if (!logback && isComposite(name) && i < end && pattern.charAt(i) == '{') {
        childEnd = optionEnd(pattern, i, end, false);
      }
      if (childEnd >= 0) {
        // Keep message-only wrappers around the message, without transforming the new fields.
        // If a wrapper also contains a prefix, keep that prefix ahead of the injected fields.
        int nested =
            findInsertion(
                pattern,
                i + 1,
                childEnd,
                logback,
                message,
                prefixSeen || wrapperStart < 0 ? tokenStart : wrapperStart);
        if (nested >= 0) {
          return nested;
        }
        i = childEnd + 1;
      }
      while (i < end && pattern.charAt(i) == '{') {
        int close = optionEnd(pattern, i, end, logback);
        if (close < 0) {
          return -1;
        }
        i = close + 1;
      }
      prefixSeen = true;
    }
    return -1;
  }

  private static boolean isLineBreak(String pattern, int offset) {
    return pattern.startsWith("%n", offset)
        && (offset + 2 == pattern.length() || !Character.isLetter(pattern.charAt(offset + 2)));
  }

  private static String inject(
      String pattern, int presentFields, boolean logback, boolean supportsEquals) {
    if (pattern == null || pattern.isEmpty() || presentFields == ALL) {
      return pattern;
    }
    int insertion = findInsertion(pattern, 0, pattern.length(), logback, true, -1);
    if (insertion < 0) {
      insertion = findInsertion(pattern, 0, pattern.length(), logback, false, -1);
    }
    if (insertion < 0) {
      insertion = pattern.length();
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
      result.append(
          logback
              ? "dd.trace_id=%X{dd.trace_id:-0} "
              : supportsEquals
                  ? "dd.trace_id=%equals{%X{dd.trace_id}}{}{0} "
                  : "dd.trace_id=%ddAgentId{dd.trace_id} ");
    }
    if ((presentFields & SPAN_ID) == 0) {
      result.append(
          logback
              ? "dd.span_id=%X{dd.span_id:-0} "
              : supportsEquals
                  ? "dd.span_id=%equals{%X{dd.span_id}}{}{0} "
                  : "dd.span_id=%ddAgentId{dd.span_id} ");
    }
    if ((presentFields & ENV) == 0) {
      result.append("dd.env=%X{dd.env} ");
    }
    if ((presentFields & VERSION) == 0) {
      result.append("dd.version=%X{dd.version} ");
    }
    if (insertion == pattern.length()
        || isLineBreak(pattern, insertion)
        || pattern.charAt(insertion) == '\n'
        || pattern.charAt(insertion) == '\r') {
      result.setLength(result.length() - 1);
    }
    return result.append(pattern, insertion, pattern.length()).toString();
  }
}

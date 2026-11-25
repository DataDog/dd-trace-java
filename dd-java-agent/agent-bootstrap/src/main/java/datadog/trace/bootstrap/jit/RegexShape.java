package datadog.trace.bootstrap.jit;

/**
 * Classifies a regex string into a shape that the string helpers can handle without {@code
 * java.util.regex}, with exactly the same results.
 *
 * <p>Shapes are deliberately narrow; anything not recognized is {@link #OTHER} and goes through
 * {@code java.util.regex}.
 */
enum RegexShape {
  /** A non-empty literal: no metacharacters, or only metacharacters escaped with a backslash. */
  LITERAL,
  /** {@code \s+}: one or more whitespace characters. */
  WHITESPACE_RUN,
  /** {@code " +"}: one or more spaces. */
  SPACE_RUN,
  /** {@code \s*X\s*}: a single literal separator surrounded by optional whitespace. */
  PADDED_SEPARATOR,
  /** {@code X\s*}: a single literal separator followed by optional whitespace. */
  TRAILING_PADDED_SEPARATOR,
  /** Anything else. */
  OTHER;

  /** Regex metacharacters, outside a character class. */
  private static final String METACHARACTERS = "\\^$.|?*+()[]{}";

  static RegexShape of(String regex) {
    if (regex.isEmpty()) {
      return OTHER;
    }
    switch (regex) {
      case "\\s+":
        return WHITESPACE_RUN;
      case " +":
        return SPACE_RUN;
      case "\\s*,\\s*":
      case "\\s*;\\s*":
      case "\\s*:\\s*":
      case "\\s*=\\s*":
        return PADDED_SEPARATOR;
      case ",\\s*":
      case ";\\s*":
      case ":\\s*":
        return TRAILING_PADDED_SEPARATOR;
      default:
        return null != literal(regex) ? LITERAL : OTHER;
    }
  }

  /** The separator character of a padded shape. */
  static char separator(String regex) {
    return regex.charAt(regex.startsWith("\\s*") ? 3 : 0);
  }

  /**
   * The literal text the regex matches, or {@code null} if it isn't a plain literal. A backslash is
   * only accepted before a non-alphanumeric ASCII character, which Java's regex syntax always
   * treats as that literal character.
   */
  static String literal(String regex) {
    StringBuilder text = null;
    int length = regex.length();
    for (int i = 0; i < length; i++) {
      char c = regex.charAt(i);
      if (c == '\\') {
        if (i + 1 >= length) {
          return null; // trailing backslash: invalid regex, let java.util.regex report it
        }
        char escaped = regex.charAt(i + 1);
        if (escaped > 0x7F || Character.isLetterOrDigit(escaped)) {
          return null; // a class (s, d), back reference (1), quote (Q) or code point escape
        }
        if (null == text) {
          text = new StringBuilder(length);
          text.append(regex, 0, i);
        }
        text.append(escaped);
        i++;
      } else if (METACHARACTERS.indexOf(c) >= 0) {
        return null;
      } else if (null != text) {
        text.append(c);
      }
    }
    String literal = null != text ? text.toString() : regex;
    return literal.isEmpty() ? null : literal;
  }

  /**
   * Whether a replacement string is free of {@code $} group references and {@code \} escapes, so
   * that it is inserted as-is.
   */
  static boolean isLiteralReplacement(String replacement) {
    return replacement.indexOf('$') < 0 && replacement.indexOf('\\') < 0;
  }

  /** Whitespace as matched by {@code \s} without {@code UNICODE_CHARACTER_CLASS}. */
  static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
  }
}

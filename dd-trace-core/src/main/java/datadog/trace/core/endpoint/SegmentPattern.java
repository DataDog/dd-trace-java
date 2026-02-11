package datadog.trace.core.endpoint;

import java.util.regex.Pattern;

/**
 * Defines the patterns used to simplify URL path segments for endpoint inference. These patterns
 * replace common parameter types (IDs, hex strings, etc.) with standardized placeholders to reduce
 * cardinality in APM metrics.
 *
 * <p>Patterns are applied in order, and the first match wins.
 */
public enum SegmentPattern {
  /**
   * Matches integers of size at least 2 digits. Examples: "123", "9876" Replacement: "{param:int}"
   */
  INTEGER(Pattern.compile("^[1-9][0-9]+$"), "{param:int}"),

  /**
   * Matches mixed strings with digits and delimiters (at least 3 chars). Must contain at least one
   * digit. Examples: "user-123", "order_456", "item.789" Replacement: "{param:int_id}"
   */
  INT_ID(Pattern.compile("^(?=.*[0-9].*)[0-9._-]{3,}$"), "{param:int_id}"),

  /**
   * Matches hexadecimal strings of size at least 6 chars. Must contain at least one decimal digit
   * (0-9). Examples: "abc123", "deadbeef", "A1B2C3" Replacement: "{param:hex}"
   */
  HEX(Pattern.compile("^(?=.*[0-9].*)[A-Fa-f0-9]{6,}$"), "{param:hex}"),

  /**
   * Matches mixed strings with hex digits and delimiters (at least 6 chars). Must contain at least
   * one decimal digit. Examples: "uuid-abc123", "token_def456" Replacement: "{param:hex_id}"
   */
  HEX_ID(Pattern.compile("^(?=.*[0-9].*)[A-Fa-f0-9._-]{6,}$"), "{param:hex_id}"),

  /**
   * Matches long strings (20+ chars) or strings with special characters. Special chars: % & ' ( ) *
   * + , : = @ Examples: "very-long-string-with-many-characters", "param%20value",
   * "user@example.com" Replacement: "{param:str}"
   */
  STRING(Pattern.compile("^(.{20,}|.*[%&'()*+,:=@].*)$"), "{param:str}");

  private final Pattern pattern;
  private final String replacement;

  SegmentPattern(Pattern pattern, String replacement) {
    this.pattern = pattern;
    this.replacement = replacement;
  }

  /**
   * Tests if the given segment matches this pattern.
   *
   * @param segment the URL path segment to test
   * @return true if the segment matches this pattern
   */
  public boolean matches(String segment) {
    return pattern.matcher(segment).matches();
  }

  /**
   * Gets the replacement string for this pattern.
   *
   * @return the replacement placeholder
   */
  public String getReplacement() {
    return replacement;
  }

  /**
   * Attempts to simplify the given segment by matching against all patterns in order. If no pattern
   * matches, returns the original segment unchanged.
   *
   * @param segment the URL path segment to simplify
   * @return the simplified segment or the original if no pattern matches
   */
  public static String simplify(String segment) {
    if (segment == null || segment.isEmpty()) {
      return segment;
    }

    for (SegmentPattern pattern : values()) {
      if (pattern.matches(segment)) {
        return pattern.getReplacement();
      }
    }

    return segment;
  }
}

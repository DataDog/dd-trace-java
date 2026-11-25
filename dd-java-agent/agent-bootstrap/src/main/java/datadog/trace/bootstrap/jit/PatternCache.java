package datadog.trace.bootstrap.jit;

import java.util.regex.Pattern;

/**
 * Tiered cache of compiled patterns, keyed by regex (see {@link TieredCache}).
 *
 * <p>A regex that fails to compile is never cached, so the caller's original method reports the
 * error.
 */
final class PatternCache {
  static final int PROMOTION_THRESHOLD = TieredCache.PROMOTION_THRESHOLD;

  private static final TieredCache patterns = new TieredCache();

  private PatternCache() {}

  /**
   * Returns the cached pattern for the regex, or {@code null} if it's still cold or invalid (the
   * caller then uses the original method). The regex must not be {@code null}.
   */
  static Pattern get(String regex) {
    Object entry = patterns.get(regex);
    if (entry instanceof Pattern) {
      return (Pattern) entry;
    }
    return entry == TieredCache.PROMOTE ? promote(regex) : null;
  }

  private static Pattern promote(String regex) {
    try {
      Pattern pattern = Pattern.compile(regex);
      patterns.put(regex, pattern);
      return pattern;
    } catch (RuntimeException e) {
      patterns.demote(regex); // invalid regex: let the original method throw
      return null;
    }
  }

  /** For tests: whether the regex has been promoted, without counting a use. */
  static boolean isCached(String regex) {
    return patterns.peek(regex) instanceof Pattern;
  }

  /** For tests. */
  static void clear() {
    patterns.clear();
  }
}

package datadog.trace.bootstrap.jit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Tiered cache of compiled patterns, keyed by regex.
 *
 * <p>A regex starts cold: callers use the original JDK method and nothing is retained. Only after
 * it has been seen {@link #PROMOTION_THRESHOLD} times is its compiled {@link Pattern} cached, so
 * regexes used once (e.g. during startup or configuration) never stay in memory. The cache is
 * bounded; once full, new regexes stay cold. Counting is approximate: races can lose increments,
 * which only delays promotion.
 */
final class PatternCache {
  static final int PROMOTION_THRESHOLD = 16;
  static final int MAX_ENTRIES = 256;

  private static final ConcurrentHashMap<String, Object> entries = new ConcurrentHashMap<>();

  private PatternCache() {}

  /** Mutable use counter for a regex that hasn't been promoted yet. */
  private static final class Counter {
    int count;
  }

  private static final Function<String, Object> NEW_COUNTER =
      new Function<String, Object>() {
        @Override
        public Object apply(String regex) {
          return new Counter();
        }
      };

  /**
   * Returns the cached pattern for the regex, or {@code null} if it's still cold (the caller then
   * uses the original method). May compile and cache it once it becomes hot; a regex that fails to
   * compile is never cached, so the caller's original method reports the error.
   */
  static Pattern get(String regex) {
    Object entry = entries.get(regex);
    if (entry instanceof Pattern) {
      return (Pattern) entry;
    }
    if (null == entry) {
      if (entries.size() >= MAX_ENTRIES) {
        return null;
      }
      entry = entries.computeIfAbsent(regex, NEW_COUNTER);
      if (entry instanceof Pattern) {
        return (Pattern) entry;
      }
    }
    Counter counter = (Counter) entry;
    if (++counter.count < PROMOTION_THRESHOLD) {
      return null;
    }
    try {
      Pattern pattern = Pattern.compile(regex);
      entries.put(regex, pattern);
      return pattern;
    } catch (RuntimeException e) {
      entries.remove(regex, counter); // invalid regex: let the original method throw
      return null;
    }
  }

  /** For tests: whether the regex has been promoted, without counting a use. */
  static boolean isCached(String regex) {
    return entries.get(regex) instanceof Pattern;
  }

  /** For tests. */
  static void clear() {
    entries.clear();
  }
}

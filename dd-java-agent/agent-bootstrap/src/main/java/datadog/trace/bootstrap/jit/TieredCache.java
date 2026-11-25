package datadog.trace.bootstrap.jit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tiered cache keyed by a string, with values built by the caller.
 *
 * <p>A key starts cold: {@link #get} counts the use and returns {@code null}, so the caller runs
 * the original code and nothing is built or kept for the key. Once the key has been seen {@link
 * #PROMOTION_THRESHOLD} times, {@code get} returns {@link #PROMOTE} until the caller stores a value
 * with {@link #put}. Keys used only a few times (e.g. during startup or configuration) never keep a
 * value in memory. The cache is bounded; once full, new keys stay cold. Counting is approximate:
 * races can lose increments, which only delays promotion.
 */
final class TieredCache {
  static final int PROMOTION_THRESHOLD = 16;
  static final int MAX_ENTRIES = 256;

  /** Returned by {@link #get} for a key that is hot but has no value yet. */
  static final Object PROMOTE = new Object();

  private final ConcurrentHashMap<String, Object> entries = new ConcurrentHashMap<>();

  /** Use counter for a key that hasn't been promoted yet. */
  private static final class Counter {
    int count;
  }

  private static final Function<String, Object> NEW_COUNTER =
      new Function<String, Object>() {
        @Override
        public Object apply(String key) {
          return new Counter();
        }
      };

  /**
   * Returns the value stored for the key, {@link #PROMOTE} if the key just became hot, or {@code
   * null} while it is cold (counting this use). The key must not be {@code null}.
   */
  Object get(String key) {
    Object entry = entries.get(key);
    if (null != entry && !(entry instanceof Counter)) {
      return entry;
    }
    return count(key, entry);
  }

  private Object count(String key, Object entry) {
    if (null == entry) {
      if (entries.size() >= MAX_ENTRIES) {
        return null;
      }
      entry = entries.computeIfAbsent(key, NEW_COUNTER);
      if (!(entry instanceof Counter)) {
        return entry;
      }
    }
    return ++((Counter) entry).count < PROMOTION_THRESHOLD ? null : PROMOTE;
  }

  /** Stores the value for a hot key, replacing any earlier value. */
  void put(String key, Object value) {
    entries.put(key, value);
  }

  /** Forgets a key whose value could not be built, so it starts cold again. */
  void demote(String key) {
    Object entry = entries.get(key);
    if (entry instanceof Counter) {
      entries.remove(key, entry);
    }
  }

  /** For tests: the stored value, without counting a use. */
  Object peek(String key) {
    Object entry = entries.get(key);
    return entry instanceof Counter ? null : entry;
  }

  /** For tests. */
  void clear() {
    entries.clear();
  }
}

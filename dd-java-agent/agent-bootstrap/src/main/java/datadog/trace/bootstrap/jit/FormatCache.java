package datadog.trace.bootstrap.jit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiered cache of compiled formats for call sites without a {@link FormatSite}, keyed by format
 * string.
 *
 * <p>Uses are counted in a small table of counters indexed by the format's hash, so cold formats
 * are never referenced, let alone retained. Once a counter reaches {@link #PROMOTION_THRESHOLD},
 * the format is compiled and cached (or marked unsupported, so it stops being counted). Counters
 * are halved every {@link #DECAY_PERIOD} counted uses, so promotion needs repeated use within a
 * window. The cache is bounded; once full, new formats stay cold.
 *
 * <p>Counting is approximate: races lose increments, and formats sharing a counter promote early.
 * Neither affects results, since cached entries are keyed by the format itself.
 */
final class FormatCache {
  static final int PROMOTION_THRESHOLD = 16;
  static final int MAX_ENTRIES = 256;
  static final int DECAY_PERIOD = 1 << 14;

  private static final int COUNTER_MASK = 1023;

  /** Marks a hot format that isn't supported. */
  private static final CompiledFormat UNSUPPORTED = CompiledFormat.parse("");

  private static final ConcurrentHashMap<String, CompiledFormat> entries =
      new ConcurrentHashMap<>();

  private static final byte[] counters = new byte[COUNTER_MASK + 1];
  private static int countedSinceDecay;

  private FormatCache() {}

  /** Returns the compiled format, or {@code null} if it's cold or unsupported. */
  static CompiledFormat get(String format) {
    if (null == format) {
      return null;
    }
    CompiledFormat compiled = entries.get(format);
    if (null != compiled) {
      return compiled == UNSUPPORTED ? null : compiled;
    }
    return record(format);
  }

  private static CompiledFormat record(String format) {
    if (entries.size() >= MAX_ENTRIES) {
      return null;
    }
    if (++countedSinceDecay >= DECAY_PERIOD) {
      decay();
    }
    int index = format.hashCode() & COUNTER_MASK;
    int count = counters[index] + 1;
    if (count < PROMOTION_THRESHOLD) {
      counters[index] = (byte) count;
      return null;
    }
    counters[index] = 0;
    CompiledFormat compiled = CompiledFormat.parse(format);
    entries.put(format, null == compiled ? UNSUPPORTED : compiled);
    return compiled;
  }

  private static void decay() {
    countedSinceDecay = 0;
    byte[] counters = FormatCache.counters;
    for (int i = 0; i < counters.length; i++) {
      counters[i] >>= 1;
    }
  }

  /** For tests: whether the format has been promoted (supported or not), without counting a use. */
  static boolean isCached(String format) {
    return entries.containsKey(format);
  }

  /** For tests. */
  static void clear() {
    entries.clear();
    for (int i = 0; i < counters.length; i++) {
      counters[i] = 0;
    }
    countedSinceDecay = 0;
  }
}

package datadog.trace.bootstrap.regex;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/** Process-wide cache of compiled {@link Pattern}s, keyed by regex reference and flags. */
@SuppressFBWarnings(
    value = "ES_COMPARING_PARAMETER_STRING_WITH_EQ",
    justification =
        "Reference comparison is the cache key by design: only repeated references are cached")
public final class RegexCache {

  static final int SIZE = 1024;
  static final int MAX_REGEX_LENGTH = 1024;
  private static final int MASK = SIZE - 1;

  private static final Entry[] TABLE = new Entry[SIZE];

  private static final String[] CANDIDATES = new String[SIZE];

  private static final int[] CANDIDATE_FLAGS = new int[SIZE];

  private static final LongAdder HITS = new LongAdder();
  private static final LongAdder MISSES = new LongAdder();
  private static final LongAdder ADMISSIONS = new LongAdder();

  private RegexCache() {}

  public static Pattern get(String regex) {
    return get(regex, 0);
  }

  public static Pattern get(String regex, int flags) {
    if (regex == null) {
      return Pattern.compile(regex, flags);
    }
    if (regex.length() > MAX_REGEX_LENGTH) {
      MISSES.increment();
      return Pattern.compile(regex, flags);
    }
    int idx = index(regex, flags);
    Entry e = TABLE[idx];
    if (e != null && e.regex == regex && e.flags == flags) {
      HITS.increment();
      return e.pattern;
    }
    MISSES.increment();
    return admit(regex, flags, idx, Pattern.compile(regex, flags));
  }

  public static long hits() {
    return HITS.sum();
  }

  public static long misses() {
    return MISSES.sum();
  }

  public static long admissions() {
    return ADMISSIONS.sum();
  }

  private static Pattern admit(String regex, int flags, int idx, Pattern pattern) {
    if (CANDIDATES[idx] == regex && CANDIDATE_FLAGS[idx] == flags) {
      TABLE[idx] = new Entry(regex, flags, pattern);
      CANDIDATES[idx] = null;
      ADMISSIONS.increment();
    } else {
      CANDIDATE_FLAGS[idx] = flags;
      CANDIDATES[idx] = regex;
    }
    return pattern;
  }

  static int index(String regex, int flags) {
    int h = System.identityHashCode(regex) ^ (flags * 0x9E3779B9);
    h ^= h >>> 16;
    h *= 0x85EBCA6B;
    h ^= h >>> 13;
    return h & MASK;
  }

  static void reset() {
    for (int i = 0; i < SIZE; i++) {
      TABLE[i] = null;
      CANDIDATES[i] = null;
      CANDIDATE_FLAGS[i] = 0;
    }
    HITS.reset();
    MISSES.reset();
    ADMISSIONS.reset();
  }

  private static final class Entry {
    final String regex;
    final int flags;
    final Pattern pattern;

    Entry(String regex, int flags, Pattern pattern) {
      this.regex = regex;
      this.flags = flags;
      this.pattern = pattern;
    }
  }
}

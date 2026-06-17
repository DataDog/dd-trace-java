package datadog.trace.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * name -&gt; id resolution shootout (the {@code keyOf} path), built on the generic {@link TagSet}.
 *
 * <ul>
 *   <li><b>tagSet</b> — {@code TagSet.Support.indexOf} over <b>static-final</b> {@code int[]
 *       hashes} / {@code String[] names} (refs fold to constants) + a parallel {@code long[] ids}.
 *   <li><b>tagSet_throughClass</b> — same, but via a {@code TagSet} <b>instance</b> (an
 *       instance-field load of hashes/names) — isolates the wrapper indirection vs static fields.
 *   <li><b>hashMap</b> — {@code HashMap<String,Long>} (boxes the value).
 *   <li><b>switch</b> — hand-written string {@code switch} (the thing keyOf replaces). At 16 cases
 *       it inlines fine; the at-scale degradation (hundreds of cases over FreqInlineSize) shows up
 *       against the real generated keyOf, not here.
 * </ul>
 *
 * <p>Two term flavors: <b>interned</b> (realistic — instrumentation passes string literals → the
 * {@code ==} fast path in eq) and <b>copies</b> (non-interned → forces {@code String.equals}).
 * Terms are hit-dominated.
 *
 * <code>
 * Apple M1 Max (10 core) - 8 threads (per-thread state) - 2 forks - Java 8 (Zulu 8.0.382)
 *
 * Benchmark                                Mode  Cnt           Score          Error  Units
 * KeyOfBenchmark.aa_baseline_termSelection thrpt   6  2743246161.5 ± 29519843.7  ops/s
 * KeyOfBenchmark.tagSet                    thrpt   6  2275407420.3 ± 35217527.6  ops/s
 * KeyOfBenchmark.tagSet_throughClass       thrpt   6  2036161909.9 ± 49813775.7  ops/s
 * KeyOfBenchmark.hashMap                   thrpt   6  1889985340.4 ± 46434121.2  ops/s
 * KeyOfBenchmark.switch_                   thrpt   6  1132557957.9 ±128775728.2  ops/s
 * // copies (non-interned): tagSet 1843M, tagSet_throughClass 1708M, hashMap 1593M, switch_ 1137M
 * </code>
 *
 * <ul>
 *   <li><b>tagSet ~2x the switch</b> (2275M vs 1133M) at only 16 cases — the gap widens toward the
 *       generated hundreds, where the switch exceeds the inline budget. The keyOf swap's win.
 *   <li><b>tagSet ~20% over HashMap</b> (2275M vs 1890M).
 *   <li><b>static ~12% over the instance</b> (tagSet 2275M vs tagSet_throughClass 2036M) — folded
 *       static-final arrays beat the instance-field load; pull {@code Data} into your own statics.
 *   <li>The switch is interning-insensitive (1133≈1137, dispatch-bound); hash contenders gain
 *       ~16-19% interned via the {@code ==} fast path.
 * </ul>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Thread)
public class KeyOfBenchmark {
  static final long UNKNOWN = 0L;

  static final String[] NAMES_IN = {
    "span.type", "component", "span.kind", "db.type", "db.instance", "db.statement",
    "peer.hostname", "peer.port", "http.method", "http.route", "http.status_code", "http.url",
    "error", "resource", "service", "operation"
  };

  /** ids parallel to NAMES_IN — id == index+1, matched across all contenders. */
  static final long[] IDS_IN =
      init(
          () -> {
            long[] ids = new long[NAMES_IN.length];
            for (int j = 0; j < ids.length; j++) {
              ids[j] = j + 1L;
            }
            return ids;
          });

  // fastest path: build once, pull into static final so the refs fold
  static final int[] HASHES;
  static final String[] NAMES;
  static final long[] IDS;

  static {
    TagSet.Data data = TagSet.Support.create(NAMES_IN);
    long[] ids = new long[data.names.length];
    for (int j = 0; j < NAMES_IN.length; j++) {
      ids[TagSet.Support.indexOf(data.hashes, data.names, NAMES_IN[j])] = IDS_IN[j];
    }
    HASHES = data.hashes;
    NAMES = data.names;
    IDS = ids;
  }

  static final Map<String, Long> HASH_MAP =
      init(
          () -> {
            Map<String, Long> m = new HashMap<>(NAMES_IN.length * 2);
            for (int j = 0; j < NAMES_IN.length; j++) {
              m.put(NAMES_IN[j], IDS_IN[j]);
            }
            return m;
          });

  /** Convenience instance — the through-the-class path (instance-field loads vs folded statics). */
  static final TagSet TAG_SET = TagSet.of(NAMES_IN);

  // hit-dominated, two misses; interned and non-interned copies
  static final String[] TERMS = {
    "span.type", "component", "span.kind", "db.type", "db.instance", "db.statement",
    "peer.hostname", "peer.port", "http.method", "http.route", "http.status_code", "http.url",
    "error", "resource", "service", "operation", "unknown.tag", "custom.attr"
  };

  static final String[] TERM_COPIES =
      init(
          () -> {
            String[] copies = new String[TERMS.length];
            for (int i = 0; i < TERMS.length; i++) {
              copies[i] = new String(TERMS[i]); // defeat interning
            }
            return copies;
          });

  int termIndex = 0; // per-thread (Scope.Thread) — no shared-counter contention under @Threads(8)

  String nextTerm() {
    int i = termIndex + 1;
    if (i >= TERMS.length) {
      i = 0;
    }
    termIndex = i;
    return TERMS[i];
  }

  String nextTermCopy() {
    int i = termIndex + 1;
    if (i >= TERM_COPIES.length) {
      i = 0;
    }
    termIndex = i;
    return TERM_COPIES[i];
  }

  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  // ---- resolvers ----
  static long tagSetKeyOf(String t) {
    int slot = TagSet.Support.indexOf(HASHES, NAMES, t); // folded static-final refs
    return slot < 0 ? UNKNOWN : IDS[slot];
  }

  static long tagSetThroughClassKeyOf(String t) {
    int slot = TAG_SET.indexOf(t); // instance-field load of hashes/names
    return slot < 0 ? UNKNOWN : IDS[slot];
  }

  static long hashMapKeyOf(String t) {
    Long v = HASH_MAP.get(t);
    return v == null ? UNKNOWN : v.longValue();
  }

  static long switchKeyOf(String t) {
    switch (t) {
      case "span.type":
        return 1L;
      case "component":
        return 2L;
      case "span.kind":
        return 3L;
      case "db.type":
        return 4L;
      case "db.instance":
        return 5L;
      case "db.statement":
        return 6L;
      case "peer.hostname":
        return 7L;
      case "peer.port":
        return 8L;
      case "http.method":
        return 9L;
      case "http.route":
        return 10L;
      case "http.status_code":
        return 11L;
      case "http.url":
        return 12L;
      case "error":
        return 13L;
      case "resource":
        return 14L;
      case "service":
        return 15L;
      case "operation":
        return 16L;
      default:
        return UNKNOWN;
    }
  }

  // ---- interned terms (realistic) ----
  @Benchmark
  public String aa_baseline_termSelection() {
    return nextTerm();
  }

  @Benchmark
  public long tagSet() {
    return tagSetKeyOf(nextTerm());
  }

  @Benchmark
  public long tagSet_throughClass() {
    return tagSetThroughClassKeyOf(nextTerm());
  }

  @Benchmark
  public long hashMap() {
    return hashMapKeyOf(nextTerm());
  }

  @Benchmark
  public long switch_() {
    return switchKeyOf(nextTerm());
  }

  // ---- non-interned copies (forces equals) ----
  @Benchmark
  public String aa_baseline_termSelectionCopy() {
    return nextTermCopy();
  }

  @Benchmark
  public long tagSet_copy() {
    return tagSetKeyOf(nextTermCopy());
  }

  @Benchmark
  public long tagSet_throughClass_copy() {
    return tagSetThroughClassKeyOf(nextTermCopy());
  }

  @Benchmark
  public long hashMap_copy() {
    return hashMapKeyOf(nextTermCopy());
  }

  @Benchmark
  public long switch_copy() {
    return switchKeyOf(nextTermCopy());
  }
}

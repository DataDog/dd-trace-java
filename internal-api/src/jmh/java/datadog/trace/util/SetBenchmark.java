package datadog.trace.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Ways to represent a small set of strings and test membership, split into hit and miss lookups
 * (different cost shapes per structure). Lookups are interned (the {@code ==} fast path); misses
 * are short and never present. Per-thread state ({@code @State(Scope.Thread)}) keeps the rotation
 * counter off the shared-write path under {@code @Threads(8)} — an earlier shared-counter version
 * capped the fast structures at a ~1.4B contention ceiling (since superseded by the numbers below).
 *
 * <ul>
 *   <li><b>stringIndexSupport (static) is the fastest membership path</b> — 2336M hit / 2170M miss.
 *       It beats the StringIndex instance ({@code stringIndex_*}) by ~7% (hit) to ~12% (miss): the
 *       instance pays an instance-field load of hashes/names, while {@code Support.indexOf} over
 *       {@code static final} arrays lets the refs fold to constants. Matches KeyOfBenchmark's ~12%
 *       static-vs- instance gap. So when the set is fixed, pull {@code Data} into your own static
 *       finals.
 *   <li><b>vs HashSet</b> — the static path is ~12% faster on hit and ~par on miss. But HashSet was
 *       noisy here (±22% error) while StringIndex was tight (±2-7%), so StringIndex also wins on
 *       predictability — and is allocation-free and positional-capable.
 *   <li>array / sortedArray / treeSet cluster ~0.65-1.0B — they compare/scan per element, so they
 *       slow on miss (hit early-exits; miss does the full scan / binary descent / tree walk).
 *       TreeSet is NOT uniquely slowest — worth it only for a custom comparator (case-insensitive,
 *       dodging {@code toLowerCase}), not speed.
 * </ul>
 *
 * <code>
 * Apple M1 Max (10 core) - 8 threads (per-thread state) - 2 forks - Java 8 (Zulu 8.0.382)
 *
 * Benchmark                         Mode  Cnt           Score           Error  Units
 * SetBenchmark.array_hit           thrpt    6   995578895.732 ±  73709080.997  ops/s
 * SetBenchmark.array_miss          thrpt    6   649860848.470 ±  32489300.626  ops/s
 * SetBenchmark.hashSet_hit         thrpt    6  2081738804.271 ± 464349157.190  ops/s
 * SetBenchmark.hashSet_miss        thrpt    6  2136501411.026 ± 474132929.024  ops/s
 * SetBenchmark.sortedArray_hit     thrpt    6   837595967.794 ± 113538780.712  ops/s
 * SetBenchmark.sortedArray_miss    thrpt    6   692064118.699 ±  25752553.077  ops/s
 * SetBenchmark.stringIndex_hit          thrpt    6  2184722734.028 ±  61054981.099  ops/s
 * SetBenchmark.stringIndex_miss         thrpt    6  1933588009.009 ± 159869680.982  ops/s
 * SetBenchmark.stringIndexSupport_hit   thrpt    6  2335685599.706 ±  52460762.937  ops/s
 * SetBenchmark.stringIndexSupport_miss  thrpt    6  2169715463.018 ± 141321499.862  ops/s
 * SetBenchmark.treeSet_hit         thrpt    6   798251906.675 ±  39041398.413  ops/s
 * SetBenchmark.treeSet_miss        thrpt    6   667078954.487 ±  56517120.187  ops/s
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Thread)
public class SetBenchmark {
  static final String[] STRINGS =
      new String[] {
        "foo",
        "bar",
        "baz",
        "quux",
        "hello",
        "world",
        "service",
        "queryString",
        "lorem",
        "ipsum",
        "dolem",
        "sit"
      };

  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  /** Present in the set (interned). */
  static final String[] HITS = STRINGS;

  /** Never present. */
  static final String[] MISSES =
      init(
          () -> {
            String[] misses = new String[STRINGS.length * 4];
            for (int i = 0; i < misses.length; ++i) {
              misses[i] = "dne-" + i;
            }
            return misses;
          });

  int hitIndex = 0; // per-thread (Scope.Thread) — no shared-counter contention under @Threads(8)
  int missIndex = 0;

  String nextHit() {
    int i = hitIndex + 1;
    if (i >= HITS.length) {
      i = 0;
    }
    hitIndex = i;
    return HITS[i];
  }

  String nextMiss() {
    int i = missIndex + 1;
    if (i >= MISSES.length) {
      i = 0;
    }
    missIndex = i;
    return MISSES[i];
  }

  static final String[] ARRAY = STRINGS;

  static boolean arrayContains(String needle) {
    for (String str : ARRAY) {
      if (needle.equals(str)) return true;
    }
    return false;
  }

  @Benchmark
  public boolean array_hit() {
    return arrayContains(nextHit());
  }

  @Benchmark
  public boolean array_miss() {
    return arrayContains(nextMiss());
  }

  static final String[] SORTED_ARRAY =
      init(
          () -> {
            String[] sorted = Arrays.copyOf(STRINGS, STRINGS.length);
            Arrays.sort(sorted);
            return sorted;
          });

  @Benchmark
  public boolean sortedArray_hit() {
    return Arrays.binarySearch(SORTED_ARRAY, nextHit()) >= 0;
  }

  @Benchmark
  public boolean sortedArray_miss() {
    return Arrays.binarySearch(SORTED_ARRAY, nextMiss()) >= 0;
  }

  static final HashSet<String> HASH_SET = new HashSet<>(Arrays.asList(STRINGS));

  @Benchmark
  public boolean hashSet_hit() {
    return HASH_SET.contains(nextHit());
  }

  @Benchmark
  public boolean hashSet_miss() {
    return HASH_SET.contains(nextMiss());
  }

  static final TreeSet<String> TREE_SET = new TreeSet<>(Arrays.asList(STRINGS));

  @Benchmark
  public boolean treeSet_hit() {
    return TREE_SET.contains(nextHit());
  }

  @Benchmark
  public boolean treeSet_miss() {
    return TREE_SET.contains(nextMiss());
  }

  static final StringIndex STRING_INDEX = StringIndex.of(STRINGS);

  @Benchmark
  public boolean stringIndex_hit() {
    return STRING_INDEX.contains(nextHit());
  }

  @Benchmark
  public boolean stringIndex_miss() {
    return STRING_INDEX.contains(nextMiss());
  }

  // The static Support path: hashes/names built once into static-final arrays (refs fold to
  // constants) and probed directly via Support.indexOf -- vs stringIndex_* above, which loads them
  // through a StringIndex instance. Mirrors KeyOfBenchmark's stringIndex (static) vs
  // stringIndex_throughClass.
  static final int[] SUPPORT_HASHES;
  static final String[] SUPPORT_NAMES;

  static {
    StringIndex.Data data = StringIndex.Support.create(STRINGS);
    SUPPORT_HASHES = data.hashes;
    SUPPORT_NAMES = data.names;
  }

  @Benchmark
  public boolean stringIndexSupport_hit() {
    return StringIndex.Support.indexOf(SUPPORT_HASHES, SUPPORT_NAMES, nextHit()) >= 0;
  }

  @Benchmark
  public boolean stringIndexSupport_miss() {
    return StringIndex.Support.indexOf(SUPPORT_HASHES, SUPPORT_NAMES, nextMiss()) >= 0;
  }
}

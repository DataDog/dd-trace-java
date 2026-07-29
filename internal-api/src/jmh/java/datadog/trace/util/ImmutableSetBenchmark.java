package datadog.trace.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Membership over a small, fixed, read-only string set shared across threads — split into hit and
 * miss lookups (different cost shapes per structure).
 *
 * <p>The set is built once and only read, so a single shared instance ({@link Scope#Benchmark})
 * read by all {@code @Threads} is realistic and contention-free. This is the read-mostly
 * counterpart to the per-thread mutable {@link SingleThreadedSetBenchmark}, and mirrors {@link
 * ImmutableMapBenchmark} on the set side. Sets in the tracer skew strongly toward this fixed,
 * read-only shape.
 *
 * <p>Strategies compared:
 *
 * <ul>
 *   <li>{@code array} / {@code sortedArray} — linear scan / binary search; slow on miss.
 *   <li>{@link HashSet} — idiomatic, fast; node-based, allocates per element.
 *   <li>{@link TreeSet} — comparator-ordered; worth it only for a custom comparator, not speed.
 *   <li>{@code tracerImmutableSet} — {@link java.util.Set#copyOf} (via {@link
 *       CollectionUtils#tryMakeImmutableSet}), the JDK's compact, array-backed immutable set
 *       ({@code ImmutableCollections.SetN}), which is what the agent actually uses for fixed config
 *       sets. Java 10+; falls back to {@code HashSet} pre-10. The realistic baseline for any
 *       flat/immutable set comparison.
 * </ul>
 *
 * <p>Lookups are interned (the {@code ==} fast path where a structure has one); misses are short
 * and never present.
 *
 * <p>Java 17 results (Apple M1, {@code @Fork(2)}, {@code @Threads(8)}; M ops/s = millions):
 *
 * <pre>{@code
 * Structure              hit     miss
 * hashSet               2159     1751    (fastest)
 * tracerImmutableSet    1946     1633    (Set.copyOf / SetN)
 * array                  926      584
 * sortedArray            664      588
 * treeSet                642      593
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>{@code HashSet} is fastest; {@link java.util.Set#copyOf} ({@code SetN}) trails by only ~10%
 *       on hit and ~7% on miss — and it's the compact, array-backed form the agent already uses for
 *       fixed config sets, so it's a strong default when the set is immutable.
 *   <li>{@code array} / {@code sortedArray} / {@code treeSet} cluster at ~0.6–0.9B — they scan,
 *       binary-search, or tree-walk per lookup, so they trail the hashed structures, most visibly
 *       on the miss path.
 * </ul>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Benchmark)
public class ImmutableSetBenchmark {
  static final String[] STRINGS = {
    "foo", "bar", "baz", "quux", "hello", "world",
    "service", "queryString", "lorem", "ipsum", "dolem", "sit"
  };

  /** Distinct String instances that are never present, for the miss path. */
  static final String[] MISSES = newMisses();

  static String[] newMisses() {
    String[] misses = new String[STRINGS.length * 4];
    for (int i = 0; i < misses.length; ++i) {
      misses[i] = "dne-" + i;
    }
    return misses;
  }

  // Built once, never mutated -- safe to share across the reader threads.
  String[] array;
  String[] sortedArray;
  HashSet<String> hashSet;
  TreeSet<String> treeSet;
  Set<String> tracerImmutableSet;

  @Setup(Level.Trial)
  public void setUp() {
    array = STRINGS;
    sortedArray = Arrays.copyOf(STRINGS, STRINGS.length);
    Arrays.sort(sortedArray);
    hashSet = new HashSet<>(Arrays.asList(STRINGS));
    treeSet = new TreeSet<>(Arrays.asList(STRINGS));
    tracerImmutableSet = CollectionUtils.tryMakeImmutableSet(Arrays.asList(STRINGS));
  }

  /** Per-thread lookup cursor so each reader thread cycles keys independently. */
  @State(Scope.Thread)
  public static class Cursor {
    int hitIndex = 0;
    int missIndex = 0;

    String nextHit() {
      int i = hitIndex + 1;
      if (i >= STRINGS.length) {
        i = 0;
      }
      hitIndex = i;
      return STRINGS[i];
    }

    String nextMiss() {
      int i = missIndex + 1;
      if (i >= MISSES.length) {
        i = 0;
      }
      missIndex = i;
      return MISSES[i];
    }
  }

  static boolean arrayContains(String[] array, String needle) {
    for (String s : array) {
      if (needle.equals(s)) {
        return true;
      }
    }
    return false;
  }

  @Benchmark
  public boolean array_hit(Cursor cursor) {
    return arrayContains(array, cursor.nextHit());
  }

  @Benchmark
  public boolean array_miss(Cursor cursor) {
    return arrayContains(array, cursor.nextMiss());
  }

  @Benchmark
  public boolean sortedArray_hit(Cursor cursor) {
    return Arrays.binarySearch(sortedArray, cursor.nextHit()) >= 0;
  }

  @Benchmark
  public boolean sortedArray_miss(Cursor cursor) {
    return Arrays.binarySearch(sortedArray, cursor.nextMiss()) >= 0;
  }

  @Benchmark
  public boolean hashSet_hit(Cursor cursor) {
    return hashSet.contains(cursor.nextHit());
  }

  @Benchmark
  public boolean hashSet_miss(Cursor cursor) {
    return hashSet.contains(cursor.nextMiss());
  }

  @Benchmark
  public boolean treeSet_hit(Cursor cursor) {
    return treeSet.contains(cursor.nextHit());
  }

  @Benchmark
  public boolean treeSet_miss(Cursor cursor) {
    return treeSet.contains(cursor.nextMiss());
  }

  @Benchmark
  public boolean tracerImmutableSet_hit(Cursor cursor) {
    return tracerImmutableSet.contains(cursor.nextHit());
  }

  @Benchmark
  public boolean tracerImmutableSet_miss(Cursor cursor) {
    return tracerImmutableSet.contains(cursor.nextMiss());
  }
}

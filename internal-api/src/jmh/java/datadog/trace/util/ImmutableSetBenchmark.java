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
 *   <li>{@link java.util.Set#copyOf} (via {@link CollectionUtils#tryMakeImmutableSet}) — the JDK's
 *       compact, array-backed immutable set ({@code ImmutableCollections.SetN}), which is what the
 *       agent actually uses for fixed config sets. Java 10+; falls back to {@code HashSet} pre-10.
 *       The realistic baseline for any flat/immutable set comparison.
 * </ul>
 *
 * <p>Lookups are interned (the {@code ==} fast path where a structure has one); misses are short
 * and never present. (Results pending a fresh multi-JVM run — {@code Set.copyOf} only materializes
 * the compact form on Java 10+.)
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
  Set<String> copyOfSet;

  @Setup(Level.Trial)
  public void setUp() {
    array = STRINGS;
    sortedArray = Arrays.copyOf(STRINGS, STRINGS.length);
    Arrays.sort(sortedArray);
    hashSet = new HashSet<>(Arrays.asList(STRINGS));
    treeSet = new TreeSet<>(Arrays.asList(STRINGS));
    copyOfSet = CollectionUtils.tryMakeImmutableSet(Arrays.asList(STRINGS));
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
  public boolean copyOf_hit(Cursor cursor) {
    return copyOfSet.contains(cursor.nextHit());
  }

  @Benchmark
  public boolean copyOf_miss(Cursor cursor) {
    return copyOfSet.contains(cursor.nextMiss());
  }
}

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
 *   <li>{@code stringIndex} — {@link StringIndex#contains} on the instance wrapper (one field load
 *       to reach the placed arrays, then an open-addressed probe).
 *   <li>{@code support} — the same probe via {@link StringIndex.Support#indexOf} over {@code static
 *       final} arrays, so the JIT folds the refs to constants and there is nothing to dereference
 *       (the hot path StringIndex recommends). The {@code stringIndex}/{@code support} pair shows
 *       the indirection cost of the wrapper.
 * </ul>
 *
 * <p>Lookups are interned (the {@code ==} fast path where a structure has one); misses are short
 * and never present.
 *
 * <p>JDK 17 results (Apple M1, quiet machine, {@code @Fork(5)}, {@code @Threads(8)}; M ops/s =
 * millions):
 *
 * <pre>{@code
 * Structure              hit     miss
 * support (static)      2320     2159    (fastest)
 * hashSet               2198     2134
 * stringIndex (inst)    2098     1548 *  (* miss bimodal -- see caveat)
 * tracerImmutableSet    1914     1663    (Set.copyOf / SetN)
 * array                  941      589
 * sortedArray            685      610
 * treeSet                657      610
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>The static {@code Support} path is the fastest — it beats {@code HashSet} on hit and miss
 *       and crushes the scan/search/tree forms.
 *   <li>{@code stringIndex} (the instance wrapper) trails {@code Support} by the field-load
 *       indirection (~10% on hit), landing near {@code HashSet} — fine off the hot path, prefer
 *       {@code Support} on it.
 *   <li>{@link java.util.Set#copyOf} ({@code SetN}, the agent's compact fixed-set form) is ~1.2x
 *       behind {@code Support} on hit but the most <i>compact</i> (~27% smaller — no cached hashes,
 *       no 2x table). So StringIndex's edge over {@code SetN} is speed + the {@code
 *       indexOf}-&gt;parallel-array capability, not footprint; over {@code HashSet} it wins both.
 *   <li>{@code array} / {@code sortedArray} / {@code treeSet} trail the hashed structures, most on
 *       miss.
 * </ul>
 *
 * <p><b>Caveat — the instance {@code stringIndex} miss is bimodal across forks</b> (confirmed at
 * {@code @Fork(10)}: 6 forks fast, 4 slow, nothing between). ~60% of forks compile to a fast mode
 * (~2000, ≈ {@code support_miss} — the wrapper indirection is then free) and ~40% to a slow mode
 * (~1070, ~half); each fork locks one at warmup. So the {@code 1548 ±27%} above is a mode-mix, not
 * noise. Cause: C2 hoists the instance field-loads ({@code this.hashes}/{@code names}) out of the
 * miss-path probe loop only in the fast mode; the static {@code Support} path const-folds those
 * refs and is never bimodal ({@code support_miss} ±0.3%). Prefer {@code Support} where miss latency
 * matters.
 */
@Fork(5) // 5 forks settle the bimodal stringIndex_miss / interface-dispatch arms (see header)
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

  // StringIndex static-Support mode: the placed arrays pulled into static final fields, so the JIT
  // folds the refs to constants and Support.indexOf has nothing to dereference (the hot path the
  // StringIndex class Javadoc recommends). Contrast support_* (these) with stringIndex_* (the
  // instance wrapper, one field load) to see the indirection cost.
  static final int[] SI_HASHES;
  static final String[] SI_NAMES;

  static {
    StringIndex.Data data = StringIndex.Support.create(STRINGS);
    SI_HASHES = data.hashes;
    SI_NAMES = data.names;
  }

  // Built once, never mutated -- safe to share across the reader threads.
  String[] array;
  String[] sortedArray;
  HashSet<String> hashSet;
  TreeSet<String> treeSet;
  Set<String> tracerImmutableSet;
  StringIndex stringIndex;

  @Setup(Level.Trial)
  public void setUp() {
    array = STRINGS;
    sortedArray = Arrays.copyOf(STRINGS, STRINGS.length);
    Arrays.sort(sortedArray);
    hashSet = new HashSet<>(Arrays.asList(STRINGS));
    treeSet = new TreeSet<>(Arrays.asList(STRINGS));
    tracerImmutableSet = CollectionUtils.tryMakeImmutableSet(Arrays.asList(STRINGS));
    stringIndex = StringIndex.of(STRINGS);
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

  @Benchmark
  public boolean stringIndex_hit(Cursor cursor) {
    return stringIndex.contains(cursor.nextHit());
  }

  @Benchmark
  public boolean stringIndex_miss(Cursor cursor) {
    return stringIndex.contains(cursor.nextMiss());
  }

  @Benchmark
  public boolean support_hit(Cursor cursor) {
    return StringIndex.Support.indexOf(SI_HASHES, SI_NAMES, cursor.nextHit()) >= 0;
  }

  @Benchmark
  public boolean support_miss(Cursor cursor) {
    return StringIndex.Support.indexOf(SI_HASHES, SI_NAMES, cursor.nextMiss()) >= 0;
  }
}

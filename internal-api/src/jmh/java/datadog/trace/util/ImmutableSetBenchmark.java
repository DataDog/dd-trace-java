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
 *   <li>{@code stringIndex_embedded} — the same probe via {@link
 *       StringIndex.EmbeddingSupport#indexOf} over {@code static final} arrays, so the JIT folds
 *       the refs to constants and there is nothing to dereference (the hot path StringIndex
 *       recommends). The {@code stringIndex}/{@code stringIndex_embedded} pair shows the
 *       indirection cost of the wrapper.
 * </ul>
 *
 * <p>Lookup variants:
 *
 * <ul>
 *   <li>{@code hit} uses the same interned strings that were inserted, exercising the identity fast
 *       path.
 *   <li>{@code hitFresh} uses equal, non-interned strings, avoiding the identity fast path. It is
 *       measured only for the hash-based structures.
 *   <li>{@code miss} uses non-interned strings that are not in the set.
 * </ul>
 *
 * <p>Results on an Apple M1 with Java 8u382, {@link BenchmarkUtils#polluteHashDispatch()} enabled,
 * {@code @Fork(5)}, and {@code @Threads(8)} (M ops/s):
 *
 * <pre>{@code
 * Structure                    hit   hitFresh    miss
 * stringIndex_embedded (static) 2098      1563    2030
 * hashSet                       1723      1276    1823
 * stringIndex (inst)            1883      1184 *  1700 *
 * tracerImmutableSet            1632      1232    1625    (SetN)
 * array                          854         -     495
 * sortedArray                    713         -     613
 * treeSet                        646         -     544
 * }</pre>
 *
 * <p>In this run:
 *
 * <ul>
 *   <li>The embedded {@code StringIndex} is fastest for all three lookup variants.
 *   <li>The {@code StringIndex} wrapper beats {@code HashSet} for interned hits. Its fresh-hit and
 *       miss results are bimodal and have lower means than {@code HashSet}; prefer the embedded
 *       form when these paths matter.
 *   <li>{@code SetN} is slower than the embedded form but about 27% smaller. StringIndex trades
 *       that space for speed and support for slot-aligned payload arrays.
 *   <li>Fresh hits are slower than misses for each hash-based structure: a matching distinct string
 *       reaches {@code equals()}, while a miss can stop on a hash mismatch.
 * </ul>
 *
 * <p><b>Caveat — the instance {@code stringIndex} miss is bimodal across forks</b> (confirmed at
 * {@code @Fork(10)}: 6 forks fast, 4 slow, nothing between). ~60% of forks compile to a fast mode
 * (~2000, ≈ {@code stringIndex_embedded_miss} — the wrapper indirection is then free) and ~40% to a
 * slow mode (~1070, ~half); each fork locks one at warmup. So the {@code 1548 ±27%} above is a
 * mode-mix, not noise. Cause: C2 hoists the instance field-loads ({@code this.hashes}/{@code
 * names}) out of the miss-path probe loop only in the fast mode; the static {@code
 * EmbeddingSupport} path const-folds those refs and is never bimodal ({@code
 * stringIndex_embedded_miss} ±0.3%). Prefer {@code EmbeddingSupport} where miss latency matters.
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

  /** Equal, non-interned copies of {@link #STRINGS} used to exercise equality. */
  static final String[] FRESH_STRINGS = newFreshStrings();

  static String[] newFreshStrings() {
    String[] fresh = new String[STRINGS.length];
    for (int i = 0; i < STRINGS.length; ++i) {
      fresh[i] = new String(STRINGS[i]);
    }
    return fresh;
  }

  static String[] newMisses() {
    String[] misses = new String[STRINGS.length * 4];
    for (int i = 0; i < misses.length; ++i) {
      misses[i] = "dne-" + i;
    }
    return misses;
  }

  // StringIndex static-EmbeddingSupport mode: the placed arrays pulled into static final fields, so
  // the JIT folds the refs to constants and EmbeddingSupport.indexOf has nothing to dereference
  // (the hot path the StringIndex class Javadoc recommends). Contrast stringIndex_embedded_*
  // (these) with stringIndex_* (the instance wrapper, one field load) to see the indirection cost.
  static final int[] SI_HASHES;
  static final String[] SI_NAMES;

  static {
    StringIndex.Data data = StringIndex.EmbeddingSupport.create(STRINGS);
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
    BenchmarkUtils.polluteHashDispatch();

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
    int hitFreshIndex = 0;
    int missIndex = 0;

    String nextHit() {
      int i = hitIndex + 1;
      if (i >= STRINGS.length) {
        i = 0;
      }
      hitIndex = i;
      return STRINGS[i];
    }

    /** See {@code hitFresh} in the class javadoc. */
    String nextHitFresh() {
      int i = hitFreshIndex + 1;
      if (i >= FRESH_STRINGS.length) {
        i = 0;
      }
      hitFreshIndex = i;
      return FRESH_STRINGS[i];
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
  public boolean hashSet_hitFresh(Cursor cursor) {
    return hashSet.contains(cursor.nextHitFresh());
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
  public boolean tracerImmutableSet_hitFresh(Cursor cursor) {
    return tracerImmutableSet.contains(cursor.nextHitFresh());
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
  public boolean stringIndex_hitFresh(Cursor cursor) {
    return stringIndex.contains(cursor.nextHitFresh());
  }

  @Benchmark
  public boolean stringIndex_miss(Cursor cursor) {
    return stringIndex.contains(cursor.nextMiss());
  }

  @Benchmark
  public boolean stringIndex_embedded_hit(Cursor cursor) {
    return StringIndex.EmbeddingSupport.contains(SI_HASHES, SI_NAMES, cursor.nextHit());
  }

  @Benchmark
  public boolean stringIndex_embedded_hitFresh(Cursor cursor) {
    return StringIndex.EmbeddingSupport.contains(SI_HASHES, SI_NAMES, cursor.nextHitFresh());
  }

  @Benchmark
  public boolean stringIndex_embedded_miss(Cursor cursor) {
    return StringIndex.EmbeddingSupport.contains(SI_HASHES, SI_NAMES, cursor.nextMiss());
  }
}

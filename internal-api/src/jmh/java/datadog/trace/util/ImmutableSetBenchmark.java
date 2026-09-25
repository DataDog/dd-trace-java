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
import org.openjdk.jmh.infra.Blackhole;

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
 * <p>Java 17 results on an Apple M1 with the front-loaded {@link BenchmarkUtils#warmUpHashDispatch}
 * pollution design, {@code @Fork(5)}, {@code @Threads(8)} (M ops/s):
 *
 * <pre>{@code
 * Structure                    hit   hitFresh    miss
 * stringIndex_embedded        2231.9   1663.2   2207.7
 * hashSet                     2172.8   1359.2   2252.9
 * tracerImmutableSet          2045.4   1394.9   1711.8
 * stringIndex (inst)          2037.6   1505.7   2060.7
 * array                        967.4        -    611.0
 * sortedArray                  691.4        -    598.6
 * treeSet                      657.2        -    607.2
 * }</pre>
 *
 * <p>In this run:
 *
 * <ul>
 *   <li>{@code stringIndex} is slightly better than {@code hashSet} on {@code hitFresh}, but not
 *       uniformly ahead across all three lookup variants the way earlier runs suggested; the two
 *       trade the lead depending on the variant.
 *   <li>The embedded {@code StringIndex} form remains at or near the front for all three lookup
 *       variants, and is now about as fast as the instance wrapper rather than clearly ahead of it.
 *   <li>{@code tracerImmutableSet} ({@code SetN}) is competitive with the hash-based structures on
 *       {@code hit}/{@code hitFresh} but falls behind on {@code miss}.
 * </ul>
 */
@Fork(5) // extra forks needed historically to settle bimodal JIT behavior on some arms
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
    // Superseded by Cursor#warmUpPollution's heavier front-load below -- this single call isn't
    // enough on its own to drive HotSpot's tiered compiler through both C1 and C2 on the shared
    // hash-dispatch call sites (see BenchmarkUtils#warmUpHashDispatch).
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

    // Front-load pollution once per trial, entirely before JMH's warmup starts: JMH
    // injects the Blackhole straight into this setup method, so no per-benchmark
    // scratch state is needed.
    @Setup(Level.Trial)
    public void warmUpPollution(Blackhole bh) {
      BenchmarkUtils.warmUpHashDispatch(bh);
    }

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

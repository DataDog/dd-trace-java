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
 * <p>Hit lookups come in two flavors, because reusing the exact same key instances for both
 * building a structure and measuring lookups against it is its own validity bug, independent of
 * hash-dispatch pollution: {@link String#equals} takes an {@code ==} fast path, and {@link
 * String#hashCode()} caches its result in a field on first call, so a key instance that was already
 * inserted (or previously looked up) pays neither real cost again.
 *
 * <ul>
 *   <li>{@code hit} -- looks up the same interned {@link #STRINGS} literals used to build every
 *       structure. This is realistic and worth keeping (fixed header/config-key lookups commonly
 *       are interned literals), but identity with the stored element is inherent to interning, not
 *       a choice this benchmark makes -- two equal literals are always the same instance.
 *   <li>{@code hitFresh} -- looks up {@link #FRESH_STRINGS}, separate {@code new String(..)}
 *       instances never touched by {@link #setUp}, so each carries an uncached hash and forces a
 *       real {@code equals()} beyond the {@code ==} check. Models keys arriving from
 *       parsing/concatenation/deserialization rather than literals. Only meaningful for the
 *       hash-based structures ({@code hashSet}, {@code tracerImmutableSet}, {@code stringIndex},
 *       {@code stringIndex_embedded}); not measured for {@code array}/{@code sortedArray}/{@code
 *       treeSet}.
 * </ul>
 *
 * <p>Misses are already representative of both effects for free: {@link #MISSES} is built via
 * concatenation (never interned) and never touched by {@link #setUp}.
 *
 * <p>Full re-run, all six structures across {@code hit}/{@code hitFresh}/{@code miss} together,
 * with {@link BenchmarkUtils#polluteHashDispatch()} in effect (Apple M1, Java 8u382 -- the
 * repo-default {@code jmh} test launcher, no {@code -PtestJvm} override --, {@code @Fork(5)},
 * {@code @Threads(8)}; M ops/s = millions):
 *
 * <pre>{@code
 * Structure                    hit   hitFresh    miss
 * stringIndex_embedded (static) 2098      1563    2030    (fastest hit and miss)
 * hashSet                       1723      1276    1823
 * stringIndex (inst)            1883      1184 *  1700 *  (* bimodal -- see caveat)
 * tracerImmutableSet            1632      1232    1625    (Set.copyOf / SetN)
 * array                          854         -     495
 * sortedArray                    713         -     613
 * treeSet                        646         -     544
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>The static {@code EmbeddingSupport} path is the fastest on both hit and miss -- it beats
 *       {@code HashSet} on both and crushes the scan/search/tree forms.
 *   <li>{@code stringIndex} (the instance wrapper) trails {@code EmbeddingSupport} by the
 *       field-load indirection. It reliably beats {@code HashSet} on {@code hit} (its actual design
 *       case: repeated lookups of a known, fixed name set). On {@code miss} and {@code hitFresh} it
 *       is <i>not</i> a reliable win -- both are bimodal across forks (see caveat below) and the
 *       mean in each case already sits at or below {@code HashSet}'s steady figure. For miss- or
 *       fresh-key-heavy membership use, prefer {@link StringIndex.EmbeddingSupport} directly rather
 *       than assuming the wrapper is strictly better than a plain {@code Set}. This doesn't apply
 *       to {@code StringIndex}'s parallel-value (map) use case ({@code mapValues}/{@code lookup})
 *       -- that win comes from avoiding boxing and node overhead entirely and is unaffected by any
 *       of this.
 *   <li>{@link java.util.Set#copyOf} ({@code SetN}, the agent's compact fixed-set form) trails
 *       {@code EmbeddingSupport} on every scenario but remains the most <i>compact</i> (~27%
 *       smaller -- no cached hashes, no 2x table). So StringIndex's edge over {@code SetN} is speed
 *       + the {@code indexOf}-&gt;parallel-array capability, not footprint.
 *   <li>{@code array} / {@code sortedArray} / {@code treeSet} trail every hashed structure, most on
 *       miss.
 *   <li>{@code hitFresh} is the <i>slowest</i> of the three scenarios for every hash-based
 *       structure -- clearly below both {@code hit} and {@code miss}, not merely below {@code hit}
 *       as previously guessed. This makes sense once the two failure shapes are compared: a miss
 *       usually short-circuits on the first hash mismatch during probing and rarely reaches {@code
 *       equals()}, while a {@code hitFresh} lookup must probe until it finds the match and pay a
 *       real, uncached {@code equals()} there -- so it is not simply "the honest version of hit",
 *       it exercises a genuinely more expensive path than either {@code hit} (cached hash + {@code
 *       ==}) or {@code miss} (hash-only rejection). Superseded an earlier partial-data guess that
 *       {@code hitFresh} would land at the same cost as {@code miss}.
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

  /**
   * Equal-content, non-interned, never-before-hashed copies of {@link #STRINGS}, built once here
   * and never touched by {@link #setUp} -- so a lookup against them can't ride the {@code ==} fast
   * path or a hash cached during set construction. See {@code hitFresh} in the class javadoc.
   */
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

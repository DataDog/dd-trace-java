package datadog.trace.util;

import datadog.trace.api.TagMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
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
 * Read-side benchmark for precomputed, immutable / read-mostly maps that are <i>shared</i> across
 * threads. Models the use case where a map is built once and then only read — often published and
 * read concurrently by many threads.
 *
 * <p>Because nothing mutates after construction, a single shared instance ({@link Scope#Benchmark})
 * read by all {@code @Threads} is realistic and contention-free. This is the read-mostly
 * counterpart to the per-thread mutable {@link SingleThreadedMapBenchmark} and the contended {@code
 * ConcurrentHashtable} / {@code ThreadSafeMap} suites.
 *
 * <p>Compares {@code get} + {@code iterate} across {@link HashMap}, {@link LinkedHashMap}, {@link
 * TreeMap}, {@link TagMap}, and {@link java.util.Map#copyOf} (via {@link
 * CollectionUtils#tryMakeImmutableMap} — the JDK's compact, array-backed {@code
 * ImmutableCollections.MapN}, which is what the agent actually uses for fixed config maps; Java
 * 10+, falls back to the input map pre-10). {@code Map.copyOf}/{@code MapN} is the honest
 * immutable-map baseline, not {@code HashMap}.
 *
 * <p>Also compared: {@link StringIndex} used as a string-&gt;int map — an open-addressed index plus
 * a slot-aligned {@code int[]} of values ({@code SI_VALUES[indexOf(key)]}). {@code
 * stringIndex_get*} goes through the instance wrapper; {@code support_get*} reads via {@code static
 * final} arrays (the JIT folds the refs). No {@code iterate} arm — StringIndex is a lookup index,
 * not an iteration structure; its map use case is the {@code indexOf}-&gt;parallel-array read.
 *
 * <p>Lookups use {@code EQUAL_KEYS} (distinct String instances) to exercise {@code equals()};
 * {@code *_sameKey} variants reuse the original interned key instances to show the identity fast
 * path — which is the common tracer case, since map keys are typically interned tag-name constants.
 *
 * <p>JDK 17 results (Apple M1, quiet machine, {@code @Fork(5)}, {@code @Threads(8)}; M ops/s).
 * {@code get} uses distinct keys (exercises {@code equals()}); {@code sameKey} reuses the interned
 * key (the {@code ==} fast path — the common tracer case):
 *
 * <pre>{@code
 * Structure              get    sameKey
 * support (static)      1498     2081    (fastest)
 * stringIndex (inst)    1363     1900
 * hashMap               1216     1850
 * linkedHashMap         1214       -
 * tagMap                1167     1386
 * tracerImmutableMap    1049     1364    (MapN)
 * treeMap                656       -
 * }</pre>
 *
 * <p>{@code iterate} (full traversal):
 *
 * <pre>{@code
 * tagMap.forEach        148    (fastest)
 * linkedHashMap         136
 * tracerImmutableMap    135    (MapN)
 * treeMap               134
 * hashMap               104
 * tagMap (iterator)      96
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>StringIndex-as-map ({@code Support}) is the fastest {@code get} — beating {@code HashMap}
 *       and {@code Map.copyOf}/{@code MapN}, most on the interned path; the instance wrapper trails
 *       it by ~10%. (vs {@code MapN} the edge is speed + the slot/parallel-array capability, not
 *       footprint — see {@link ImmutableSetBenchmark}.)
 *   <li>{@code TagMap.forEach} (148) beats its own {@code iterator} (96) by ~1.5x: TagMap's
 *       structure makes a faithful external {@code Iterator} expensive (externalized cursor +
 *       skip-empty + per-call re-entry + the iterator allocation) — all of which internal {@code
 *       forEach} avoids. Traverse TagMap via {@code forEach}, never its iterator; that gap only
 *       widens as TagMap's entry model grows.
 * </ul>
 */
// @Fork(5): get_tracerImmutableMap* (MapN reached via interface dispatch) is JIT-bimodal at fewer
// forks — 5
// forks resolves it (get_tracerImmutableMap_sameKey measured ±90% at @Fork(2) -> ±1.8% at
// @Fork(5)).
@Fork(5)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Benchmark)
public class ImmutableMapBenchmark {
  static final String[] INSERTION_KEYS = {
    "foo", "bar", "baz", "quux", "foobar", "foobaz", "key0", "key1", "key2", "key3"
  };

  // Distinct String instances (not the literals used to build the maps) so lookups exercise
  // equals(), not identity -- the realistic case for keys arriving from parsing/decoding.
  static final String[] EQUAL_KEYS = newEqualKeys();

  static String[] newEqualKeys() {
    String[] keys = new String[INSERTION_KEYS.length];
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      keys[i] = new String(INSERTION_KEYS[i]);
    }
    return keys;
  }

  static void fill(Map<String, Integer> map) {
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      map.put(INSERTION_KEYS[i], i);
    }
  }

  // StringIndex as a string->int map: an open-addressed index plus a slot-aligned int[] of values
  // (VALUES[indexOf(key)]). support_* reads via static final arrays (JIT folds the refs to
  // constants); stringIndex_* goes through the instance wrapper. Both share one placement --
  // StringIndex.of and Support.create place identically -- so SI_VALUES aligns with either.
  static final int[] SI_HASHES;
  static final String[] SI_NAMES;
  static final int[] SI_VALUES;

  static {
    StringIndex.Data data = StringIndex.EmbeddingSupport.create(INSERTION_KEYS);
    SI_HASHES = data.hashes;
    SI_NAMES = data.names;
    SI_VALUES = new int[SI_HASHES.length];
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      SI_VALUES[StringIndex.EmbeddingSupport.indexOf(SI_HASHES, SI_NAMES, INSERTION_KEYS[i])] = i;
    }
  }

  // Built once, never mutated -- safe to share across the reader threads.
  HashMap<String, Integer> hashMap;
  LinkedHashMap<String, Integer> linkedHashMap;
  TreeMap<String, Integer> treeMap;
  TagMap tagMap;
  Map<String, Integer> tracerImmutableMap;
  StringIndex stringIndex;

  @Setup(Level.Trial)
  public void setUp() {
    hashMap = new HashMap<>();
    fill(hashMap);
    linkedHashMap = new LinkedHashMap<>();
    fill(linkedHashMap);
    treeMap = new TreeMap<>();
    fill(treeMap);
    tagMap = TagMap.create();
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      tagMap.set(INSERTION_KEYS[i], i); // primitive support
    }
    // JDK compact immutable map (MapN on Java 10+); the agent's actual fixed-map representation.
    tracerImmutableMap = CollectionUtils.tryMakeImmutableMap(hashMap);
    stringIndex = StringIndex.of(INSERTION_KEYS);
  }

  /** Per-thread lookup cursor so each reader thread cycles keys independently. */
  @State(Scope.Thread)
  public static class Cursor {
    int index = 0;

    String nextKey() {
      return nextKey(EQUAL_KEYS);
    }

    String nextKey(String[] keys) {
      if (++index >= keys.length) index = 0;
      return keys[index];
    }
  }

  @Benchmark
  public Integer get_hashMap(Cursor cursor) {
    return hashMap.get(cursor.nextKey());
  }

  @Benchmark
  public Integer get_hashMap_sameKey(Cursor cursor) {
    return hashMap.get(cursor.nextKey(INSERTION_KEYS));
  }

  @Benchmark
  public void iterate_hashMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : hashMap.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public Integer get_linkedHashMap(Cursor cursor) {
    return linkedHashMap.get(cursor.nextKey());
  }

  @Benchmark
  public void iterate_linkedHashMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : linkedHashMap.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public Integer get_treeMap(Cursor cursor) {
    return treeMap.get(cursor.nextKey());
  }

  @Benchmark
  public void iterate_treeMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : treeMap.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public int get_tagMap(Cursor cursor) {
    return tagMap.getInt(cursor.nextKey());
  }

  @Benchmark
  public int get_tagMap_sameKey(Cursor cursor) {
    return tagMap.getInt(cursor.nextKey(INSERTION_KEYS));
  }

  @Benchmark
  public void iterate_tagMap(Blackhole blackhole) {
    for (TagMap.EntryReader entry : tagMap) {
      blackhole.consume(entry.tag());
      blackhole.consume(entry.intValue());
    }
  }

  @Benchmark
  public void iterate_tagMap_forEach(Blackhole blackhole) {
    // Taking advantage of passthrough of contextObj to avoid capturing lambda
    tagMap.forEach(
        blackhole,
        (bh, entry) -> {
          bh.consume(entry.tag());
          bh.consume(entry.intValue());
        });
  }

  @Benchmark
  public Integer get_tracerImmutableMap(Cursor cursor) {
    return tracerImmutableMap.get(cursor.nextKey());
  }

  @Benchmark
  public Integer get_tracerImmutableMap_sameKey(Cursor cursor) {
    return tracerImmutableMap.get(cursor.nextKey(INSERTION_KEYS));
  }

  @Benchmark
  public void iterate_tracerImmutableMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : tracerImmutableMap.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public int stringIndex_get(Cursor cursor) {
    return SI_VALUES[stringIndex.indexOf(cursor.nextKey())];
  }

  @Benchmark
  public int stringIndex_get_sameKey(Cursor cursor) {
    return SI_VALUES[stringIndex.indexOf(cursor.nextKey(INSERTION_KEYS))];
  }

  @Benchmark
  public int support_get(Cursor cursor) {
    return SI_VALUES[StringIndex.EmbeddingSupport.indexOf(SI_HASHES, SI_NAMES, cursor.nextKey())];
  }

  @Benchmark
  public int support_get_sameKey(Cursor cursor) {
    return SI_VALUES[
        StringIndex.EmbeddingSupport.indexOf(SI_HASHES, SI_NAMES, cursor.nextKey(INSERTION_KEYS))];
  }
}

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
 * <p>Lookups use {@code EQUAL_KEYS} (distinct String instances) to exercise {@code equals()};
 * {@code *_sameKey} variants reuse the original interned key instances to show the identity fast
 * path — which is the common tracer case, since map keys are typically interned tag-name constants.
 * (Results pending a fresh multi-JVM run — {@code Map.copyOf} only materializes the compact form on
 * Java 10+.)
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

  // Built once, never mutated -- safe to share across the reader threads.
  HashMap<String, Integer> hashMap;
  LinkedHashMap<String, Integer> linkedHashMap;
  TreeMap<String, Integer> treeMap;
  TagMap tagMap;
  Map<String, Integer> tracerImmutableMap;

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
}

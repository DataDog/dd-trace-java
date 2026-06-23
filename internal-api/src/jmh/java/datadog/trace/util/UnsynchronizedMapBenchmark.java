package datadog.trace.util;

import datadog.trace.api.TagMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 *
 *
 * <ul>
 *   Benchmark comparing different Map-s...
 *   <li>(RECOMMENDED) HashMap - for fastest lookups - (not typically needed for tags)
 *   <li>(RECOMMENDED) TagMap - for storing tags - especially if copying between maps or using
 *       builders
 *   <li>TreeMap - better for custom Comparators - case-insensitive Maps (see
 *       CaseInsensitiveMapBenchmark)
 *   <li>LinkedHashMap - only when insertion order is needed
 * </ul>
 *
 * <p>TagMap is the preferred way to store tags.
 *
 * <p>TagMap excels at storing primitives, copying between TagMap instances, and builder idioms.
 *
 * <p>Iterator traversal with TagMap is relatively slow, but TagMap#forEach is on par (and slightly)
 * faster than traditional map entry iteration.
 *
 * <p>HashMap & LinkedHashMap perform equally well on get operations.
 *
 * <p>HashMap is 2x faster throughput-wise to create and has less memory overhead because there's no
 * linked list to capture insertion order.
 *
 * <p>TreeMap is useful when a custom Comparator is needed -- see CaseInsensitiveMapBenchmark
 *
 * <p>HashMap & TagMap also perform exceedingly well in cases where the exact same object is used
 * for put & get operations. e.g. when using String literals or Class literals as keys <code>
 * MacBook M1 1 thread (Java 21)
 *
 * Benchmark                                         Mode  Cnt          Score          Error  Units
 * UnsynchronizedMapBenchmark.clone_hashMap         thrpt    6   12482267.775 ±   236852.198  ops/s
 * UnsynchronizedMapBenchmark.clone_linkedHashMap   thrpt    6   12414187.888 ±   224418.265  ops/s
 * UnsynchronizedMapBenchmark.clone_tagMap          thrpt    6   49638156.234 ±  2972608.986  ops/s
 * UnsynchronizedMapBenchmark.clone_treeMap         thrpt    6   16201216.086 ±   619985.352  ops/s
 *
 * UnsynchronizedMapBenchmark.create_hashMap        thrpt    6   22534042.260 ±   819970.046  ops/s
 * UnsynchronizedMapBenchmark.create_hashMap_sized  thrpt    6   21871270.375 ±   893842.109  ops/s
 * UnsynchronizedMapBenchmark.create_linkedHashMap  thrpt    6   12905731.242 ±  8930007.156  ops/s
 * UnsynchronizedMapBenchmark.create_tagMap         thrpt    6   15794277.380 ±  6069426.265  ops/s
 * UnsynchronizedMapBenchmark.create_treeMap        thrpt    6    4711961.814 ±    48582.934  ops/s
 *
 * UnsynchronizedMapBenchmark.get_hashMap           thrpt    6  212201631.841 ±  6223069.782  ops/s
 * UnsynchronizedMapBenchmark.get_hashMap_sameKey   thrpt    6  392053406.085 ±  3938305.125  ops/s
 * UnsynchronizedMapBenchmark.get_linkedHashMap     thrpt    6  210734968.352 ±  3627805.282  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap            thrpt    6  201864656.534 ±  4596147.771  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap_sameKey    thrpt    6  256311645.716 ± 13315886.308  ops/s
 * UnsynchronizedMapBenchmark.get_treeMap           thrpt    6   94606404.423 ±   806879.890  ops/s
 * </code> <code>
 * MacBook M1 with 8 threads (Java 17)
 *
 * Benchmark                                             Mode  Cnt           Score           Error  Units
 * UnsynchronizedMapBenchmark.clone_hashMap             thrpt    6    64114261.922 ±   2714625.721  ops/s
 * UnsynchronizedMapBenchmark.clone_linkedHashMap       thrpt    6    58377772.312 ±   1886458.903  ops/s
 * UnsynchronizedMapBenchmark.clone_tagMap              thrpt    6   294689216.889 ±   2109031.601  ops/s
 * UnsynchronizedMapBenchmark.clone_treeMap             thrpt    6    95734227.366 ±   4991257.908  ops/s
 *
 * UnsynchronizedMapBenchmark.create_hashMap            thrpt    6   138186878.397 ±   8061849.083  ops/s
 * UnsynchronizedMapBenchmark.create_hashMap_sized      thrpt    6   134663715.019 ±   3647180.727  ops/s
 * UnsynchronizedMapBenchmark.create_linkedHashMap      thrpt    6   100174160.624 ±  15100786.744  ops/s
 * UnsynchronizedMapBenchmark.create_tagMap             thrpt    6   100393577.047 ±  31239670.187  ops/s
 * UnsynchronizedMapBenchmark.create_tagMap_via_ledger  thrpt    6    66617980.502 ±   7150404.185  ops/s
 * UnsynchronizedMapBenchmark.create_treeMap            thrpt    6    35675941.185 ±   1336913.277  ops/s
 *
 * UnsynchronizedMapBenchmark.get_hashMap               thrpt    6  1188970326.797 ±  12769138.695  ops/s
 * UnsynchronizedMapBenchmark.get_hashMap_sameKey       thrpt    6  1775131917.376 ±  36253403.407  ops/s
 * UnsynchronizedMapBenchmark.get_linkedHashMap         thrpt    6  1116040635.574 ± 171675606.827  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap                thrpt    6  1096185164.767 ±  86073700.284  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap_sameKey        thrpt    6  1421384314.806 ±  41585905.864  ops/s
 * UnsynchronizedMapBenchmark.get_treeMap               thrpt    6   609161698.455 ±  52856650.957  ops/s
 *
 * UnsynchronizedMapBenchmark.iterate_hashMap           thrpt    6   105299838.607 ±   2351744.650  ops/s
 * UnsynchronizedMapBenchmark.iterate_linkedHashMap     thrpt    6   128496701.159 ±   4733810.851  ops/s
 * UnsynchronizedMapBenchmark.iterate_tagMap            thrpt    6    93444577.025 ±   3013254.073  ops/s
 * UnsynchronizedMapBenchmark.iterate_tagMap_forEach    thrpt    6   147636746.282 ±  13994246.571  ops/s
 * UnsynchronizedMapBenchmark.iterate_treeMap           thrpt    6   128732613.579 ±   4947877.215  ops/s
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class UnsynchronizedMapBenchmark {
  static final String[] INSERTION_KEYS = {
    "foo", "bar", "baz", "quux", "foobar", "foobaz", "key0", "key1", "key2", "key3"
  };

  static final String[] EQUAL_KEYS =
      init(
          () -> {
            String[] keys = new String[INSERTION_KEYS.length];
            for (int i = 0; i < INSERTION_KEYS.length; ++i) {
              keys[i] = new String(INSERTION_KEYS[i]);
            }
            return keys;
          });

  @State(Scope.Thread)
  public static class BenchmarkState {
    int index = 0;

    String nextLookupKey() {
      return nextLookupKey(EQUAL_KEYS);
    }

    String nextLookupKey(String[] keys) {
      if (++index >= keys.length) index = 0;
      return keys[index];
    }
  }

  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  static void fill(Map<String, Integer> map) {
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      map.put(INSERTION_KEYS[i], i);
    }
  }

  static HashMap<String, Integer> _create_hashMap() {
    HashMap<String, Integer> map = new HashMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public Map<String, Integer> create_hashMap() {
    return _create_hashMap();
  }

  @Benchmark
  public Map<String, Integer> create_hashMap_sized() {
    return _create_hashMap_sized();
  }

  static final HashMap<String, Integer> HASH_MAP = _create_hashMap();

  @Benchmark
  public Integer get_hashMap(BenchmarkState state) {
    return HASH_MAP.get(state.nextLookupKey());
  }

  @Benchmark
  public void iterate_hashMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : HASH_MAP.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public Integer get_hashMap_sameKey(BenchmarkState state) {
    return HASH_MAP.get(state.nextLookupKey(INSERTION_KEYS));
  }

  @Benchmark
  public Map<String, Integer> clone_hashMap() {
    return new HashMap<>(HASH_MAP);
  }

  static Map<String, Integer> _create_hashMap_sized() {
    // Sizing is preferable for large maps, but in practice, most of our maps typically fall within
    // the default
    HashMap<String, Integer> map = new HashMap<>(INSERTION_KEYS.length);
    fill(map);
    return map;
  }

  static TreeMap<String, Integer> _create_treeMap() {
    TreeMap<String, Integer> map = new TreeMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public TreeMap<String, Integer> create_treeMap() {
    return _create_treeMap();
  }

  static final TreeMap<String, Integer> TREE_MAP = _create_treeMap();

  @Benchmark
  public Integer get_treeMap(BenchmarkState state) {
    return TREE_MAP.get(state.nextLookupKey());
  }

  @Benchmark
  public void iterate_treeMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : TREE_MAP.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public TreeMap<String, Integer> clone_treeMap() {
    TreeMap<String, Integer> map = new TreeMap<>();
    map.putAll(TREE_MAP);
    return map;
  }

  static LinkedHashMap<String, Integer> _create_linkedHashMap() {
    LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public LinkedHashMap<String, Integer> create_linkedHashMap() {
    return _create_linkedHashMap();
  }

  static final LinkedHashMap<String, Integer> LINKED_HASH_MAP = _create_linkedHashMap();

  @Benchmark
  public Integer get_linkedHashMap(BenchmarkState state) {
    return LINKED_HASH_MAP.get(state.nextLookupKey());
  }

  @Benchmark
  public void iterate_linkedHashMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : TREE_MAP.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public LinkedHashMap<String, Integer> clone_linkedHashMap() {
    return new LinkedHashMap<>(LINKED_HASH_MAP);
  }

  static TagMap _create_tagMap() {
    TagMap map = TagMap.create();
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      map.set(INSERTION_KEYS[i], i); // taking advantage of primitive support
    }
    return map;
  }

  @Benchmark
  public TagMap create_tagMap() {
    return _create_tagMap();
  }

  @Benchmark
  public TagMap create_tagMap_via_ledger() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      ledger.set(INSERTION_KEYS[i], i); // taking advantage of primitive support
    }
    return ledger.build();
  }

  static final TagMap TAG_MAP = _create_tagMap();

  @Benchmark
  public int get_tagMap(BenchmarkState state) {
    return TAG_MAP.getInt(state.nextLookupKey());
  }

  @Benchmark
  public int get_tagMap_sameKey(BenchmarkState state) {
    return TAG_MAP.getInt(state.nextLookupKey(INSERTION_KEYS));
  }

  @Benchmark
  public void iterate_tagMap(Blackhole blackhole) {
    for (TagMap.EntryReader entry : TAG_MAP) {
      blackhole.consume(entry.tag());
      blackhole.consume(entry.intValue());
    }
  }

  @Benchmark
  public void iterate_tagMap_forEach(Blackhole blackhole) {
    // Taking advantage of passthrough of contextObj to avoid capturing lambda
    TAG_MAP.forEach(
        blackhole,
        (bh, entry) -> {
          bh.consume(entry.tag());
          bh.consume(entry.intValue());
        });
  }

  @Benchmark
  public TagMap clone_tagMap() {
    return TAG_MAP.copy();
  }
}

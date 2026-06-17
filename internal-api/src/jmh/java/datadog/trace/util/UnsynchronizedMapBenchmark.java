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
 *   <li>TagSet + parallel value array - for a FIXED (build-once, read-only) map whose key set is
 *       known up front: the keys go in a {@link TagSet} and the values in a parallel array indexed
 *       by the slot {@code indexOf} returns. Fastest get, no per-lookup allocation, no node chasing
 *       - but it can't change after construction (see get_tagSetMap).
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
 * MacBook M1 with 8 threads (Java 21)
 *
 * Benchmark                                             Mode  Cnt           Score           Error  Units
 * UnsynchronizedMapBenchmark.clone_hashMap             thrpt    6    89645484.526 ±   6546683.185  ops/s
 * UnsynchronizedMapBenchmark.clone_linkedHashMap       thrpt    6    78233577.417 ±   7204526.742  ops/s
 * UnsynchronizedMapBenchmark.clone_tagMap              thrpt    6   315228772.058 ±  20689692.104  ops/s
 * UnsynchronizedMapBenchmark.clone_treeMap             thrpt    6   102416350.341 ±   7258040.561  ops/s
 *
 * UnsynchronizedMapBenchmark.create_hashMap            thrpt    6   150462966.692 ±  11243713.572  ops/s
 * UnsynchronizedMapBenchmark.create_hashMap_sized      thrpt    6   111213025.138 ±   4593366.916  ops/s
 * UnsynchronizedMapBenchmark.create_linkedHashMap      thrpt    6    80882399.133 ±  19567359.487  ops/s
 * UnsynchronizedMapBenchmark.create_tagMap             thrpt    6    93026443.634 ±  11831456.794  ops/s
 * UnsynchronizedMapBenchmark.create_tagMap_via_ledger  thrpt    6    70769351.353 ±   3821543.185  ops/s
 * UnsynchronizedMapBenchmark.create_treeMap            thrpt    6    32737595.187 ±   2638992.844  ops/s
 *
 * UnsynchronizedMapBenchmark.get_hashMap               thrpt    6  1154522356.093 ± 116525174.735  ops/s
 * UnsynchronizedMapBenchmark.get_hashMap_sameKey       thrpt    6  1760800709.734 ±  33551896.166  ops/s
 * UnsynchronizedMapBenchmark.get_linkedHashMap         thrpt    6  1191208257.933 ±  49810465.132  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap                thrpt    6   933455574.646 ± 154146815.295  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap_sameKey        thrpt    6  1138764608.359 ±  88352911.617  ops/s
 * UnsynchronizedMapBenchmark.get_treeMap               thrpt    6   490872723.682 ±  87017311.892  ops/s
 *
 * UnsynchronizedMapBenchmark.iterate_hashMap           thrpt    6   351222668.708 ±  35242914.752  ops/s
 * UnsynchronizedMapBenchmark.iterate_linkedHashMap     thrpt    6   406635839.285 ±  55990655.235  ops/s
 * UnsynchronizedMapBenchmark.iterate_tagMap            thrpt    6   185264584.604 ±  15137886.028  ops/s
 * UnsynchronizedMapBenchmark.iterate_tagMap_forEach    thrpt    6   422407681.630 ±  19493455.109  ops/s
 * UnsynchronizedMapBenchmark.iterate_treeMap           thrpt    6   392884747.896 ±  80190674.417  ops/s
 * </code>
 *
 * <p>A TagSet + parallel int[] used as a fixed (build-once) map is the fastest get here -- ahead of
 * HashMap in both the rotating-key and same-key cases -- and the most predictable (±1-2% vs
 * HashMap's ~5% and TagMap's ~12%). It pays no boxing (int[] values), chases no node, and allocates
 * nothing per lookup. It only applies when the key set is fixed at construction. <code>
 * Fixed-map get comparison incl. TagSet + parallel int[] (Apple M1 Max, 8 threads, Java 8 Zulu 8.0.382)
 *
 * Benchmark                                          Mode  Cnt           Score          Error  Units
 * UnsynchronizedMapBenchmark.get_hashMap            thrpt    6  1086382687.356 ±  52784044.910  ops/s
 * UnsynchronizedMapBenchmark.get_hashMap_sameKey    thrpt    6  1254600761.612 ±  33664831.344  ops/s
 * UnsynchronizedMapBenchmark.get_linkedHashMap      thrpt    6  1055047178.664 ±  44355203.950  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap             thrpt    6   906953688.631 ± 112749682.884  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap_sameKey     thrpt    6  1055399886.424 ± 162974805.646  ops/s
 * UnsynchronizedMapBenchmark.get_tagSetMap          thrpt    6  1168134502.026 ±  23922240.061  ops/s
 * UnsynchronizedMapBenchmark.get_tagSetMap_sameKey  thrpt    6  1379540570.008 ±  15177042.759  ops/s
 * UnsynchronizedMapBenchmark.get_treeMap            thrpt    6   590656771.941 ±  63177685.351  ops/s
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

  static int sharedLookupIndex = 0;

  static String nextLookupKey() {
    return nextLookupKey(EQUAL_KEYS);
  }

  static String nextLookupKey(String[] keys) {
    int localIndex = ++sharedLookupIndex;
    if (localIndex >= keys.length) {
      sharedLookupIndex = localIndex = 0;
    }
    return keys[localIndex];
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
  public Integer get_hashMap() {
    return HASH_MAP.get(nextLookupKey());
  }

  @Benchmark
  public void iterate_hashMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : HASH_MAP.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public Integer get_hashMap_sameKey() {
    return HASH_MAP.get(nextLookupKey(INSERTION_KEYS));
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
  public Integer get_treeMap() {
    return TREE_MAP.get(nextLookupKey());
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
  public Integer get_linkedHashMap() {
    return LINKED_HASH_MAP.get(nextLookupKey());
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
  public int get_tagMap() {
    return TAG_MAP.getInt(nextLookupKey());
  }

  @Benchmark
  public int get_tagMap_sameKey() {
    return TAG_MAP.getInt(nextLookupKey(INSERTION_KEYS));
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

  // TagSet + a parallel value array used as a FIXED (build-once, read-only) map. The keys are known
  // up front, so they go in a TagSet and the values in a plain array indexed by the slot that
  // Support.indexOf returns. There is no node, no Entry, and no per-lookup allocation -- a get is a
  // hash probe (with an interned == fast path) plus one array load. Pull the Data into your own
  // static finals (as below) so the hashes/names refs fold to constants -- same static-vs-instance
  // win SetBenchmark and KeyOfBenchmark measure. The values live in a parallel int[] -- no boxing,
  // the same primitive-value advantage TagMap.getInt has over a HashMap<String, Integer>, whose
  // value type structurally forces the box. The trade-off: it cannot change after construction.
  static final int[] TAG_SET_HASHES;
  static final String[] TAG_SET_NAMES;
  static final int[] TAG_SET_VALUES;

  static {
    TagSet.Data data = TagSet.Support.create(INSERTION_KEYS);
    int[] values = new int[data.names.length];
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      values[TagSet.Support.indexOf(data.hashes, data.names, INSERTION_KEYS[i])] = i;
    }
    TAG_SET_HASHES = data.hashes;
    TAG_SET_NAMES = data.names;
    TAG_SET_VALUES = values;
  }

  @Benchmark
  public int get_tagSetMap() {
    int slot = TagSet.Support.indexOf(TAG_SET_HASHES, TAG_SET_NAMES, nextLookupKey());
    return slot < 0 ? -1 : TAG_SET_VALUES[slot];
  }

  @Benchmark
  public int get_tagSetMap_sameKey() {
    int slot = TagSet.Support.indexOf(TAG_SET_HASHES, TAG_SET_NAMES, nextLookupKey(INSERTION_KEYS));
    return slot < 0 ? -1 : TAG_SET_VALUES[slot];
  }
}

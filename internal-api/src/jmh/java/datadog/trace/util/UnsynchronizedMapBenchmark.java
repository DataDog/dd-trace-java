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
 *   <li>(RECOMMENDED) HashMap - fastest lookups among general-purpose (mutable) maps - (not
 *       typically needed for tags; a fixed StringIndex map below is faster still when the keys are
 *       known)
 *   <li>(RECOMMENDED) TagMap - for storing tags - especially if copying between maps or using
 *       builders
 *   <li>TreeMap - better for custom Comparators - case-insensitive Maps (see
 *       CaseInsensitiveMapBenchmark)
 *   <li>LinkedHashMap - only when insertion order is needed
 *   <li>StringIndex + parallel value array - for a FIXED (build-once, read-only) map whose key set
 *       is known up front: the keys go in a {@link StringIndex} and the values in a parallel array
 *       indexed by the slot {@code indexOf} returns. Fastest get, no per-lookup allocation, no node
 *       chasing - but it can't change after construction (see get_stringIndexMap).
 * </ul>
 *
 * <p>TagMap is the preferred way to store tags.
 *
 * <p>TagMap excels at storing primitives, copying between TagMap instances, and builder idioms.
 *
 * <p>Iterator traversal with TagMap is relatively slow, but TagMap#forEach matches the fastest map
 * iterators (LinkedHashMap/TreeMap) and far outpaces HashMap entry-set iteration.
 *
 * <p>HashMap & LinkedHashMap perform equally well on get operations.
 *
 * <p>HashMap is ~1.4x faster throughput-wise to create than LinkedHashMap and has less memory
 * overhead because there's no linked list to capture insertion order.
 *
 * <p>TreeMap is useful when a custom Comparator is needed -- see CaseInsensitiveMapBenchmark
 *
 * <p>HashMap & TagMap also perform exceedingly well in cases where the exact same object is used
 * for put & get operations. e.g. when using String literals or Class literals as keys.
 *
 * <p>A StringIndex + parallel int[] used as a fixed (build-once) map is the fastest get here --
 * ~30% ahead of HashMap on the rotating-key path and ~50% ahead on the same-key path, where it
 * sustains 5.4B ops/s at the tightest error in the table (±1.3%). It pays no boxing (int[] values),
 * chases no node, and allocates nothing per lookup. It only applies when the key set is fixed at
 * construction. <code>
 * Apple M1 Max (10 core), macOS 26.4.1 -- 8 threads (per-thread state), 2 forks -- Java 17 (Zulu 17.42.19, 17.0.7+7-LTS)
 *
 * Benchmark                                             Mode  Cnt           Score           Error  Units
 * UnsynchronizedMapBenchmark.clone_hashMap             thrpt    6    69903436.959 ±   8570506.651  ops/s
 * UnsynchronizedMapBenchmark.clone_linkedHashMap       thrpt    6    85857078.271 ±   6500998.510  ops/s
 * UnsynchronizedMapBenchmark.clone_tagMap              thrpt    6   293226423.495 ±  40922964.340  ops/s
 * UnsynchronizedMapBenchmark.clone_treeMap             thrpt    6    95345978.653 ±  19359076.478  ops/s
 *
 * UnsynchronizedMapBenchmark.create_hashMap            thrpt    6   154092654.362 ±   4062613.480  ops/s
 * UnsynchronizedMapBenchmark.create_hashMap_sized      thrpt    6   146583930.032 ±   4457830.615  ops/s
 * UnsynchronizedMapBenchmark.create_linkedHashMap      thrpt    6   111282881.273 ±  11735323.503  ops/s
 * UnsynchronizedMapBenchmark.create_tagMap             thrpt    6    92286566.881 ±  16770930.695  ops/s
 * UnsynchronizedMapBenchmark.create_tagMap_via_ledger  thrpt    6    71399936.094 ±   8077504.597  ops/s
 * UnsynchronizedMapBenchmark.create_treeMap            thrpt    6    35930407.162 ±    590070.611  ops/s
 *
 * UnsynchronizedMapBenchmark.get_hashMap               thrpt    6  1897568575.023 ±  47092787.708  ops/s
 * UnsynchronizedMapBenchmark.get_hashMap_sameKey       thrpt    6  3544716454.416 ± 192397957.653  ops/s
 * UnsynchronizedMapBenchmark.get_linkedHashMap         thrpt    6  1871397460.306 ±  51848940.996  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap                thrpt    6  1706422514.145 ±  91472057.777  ops/s
 * UnsynchronizedMapBenchmark.get_tagMap_sameKey        thrpt    6  2205821374.441 ± 108659512.329  ops/s
 * UnsynchronizedMapBenchmark.get_stringIndexMap             thrpt    6  2448917198.752 ± 105399021.596  ops/s
 * UnsynchronizedMapBenchmark.get_stringIndexMap_sameKey     thrpt    6  5358887465.195 ±  70196881.552  ops/s
 * UnsynchronizedMapBenchmark.get_treeMap               thrpt    6   704782343.575 ±  52129796.457  ops/s
 *
 * UnsynchronizedMapBenchmark.iterate_hashMap           thrpt    6   132215205.201 ±   9079485.505  ops/s
 * UnsynchronizedMapBenchmark.iterate_linkedHashMap     thrpt    6   343848177.356 ±  13084730.321  ops/s
 * UnsynchronizedMapBenchmark.iterate_tagMap            thrpt    6   140210161.690 ±  13645098.317  ops/s
 * UnsynchronizedMapBenchmark.iterate_tagMap_forEach    thrpt    6   354398629.295 ±   5906534.357  ops/s
 * UnsynchronizedMapBenchmark.iterate_treeMap           thrpt    6   344428565.523 ±  11100737.787  ops/s
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Thread)
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

  int lookupIndex = 0; // per-thread (Scope.Thread) — no shared-counter contention under @Threads(8)

  String nextLookupKey() {
    return nextLookupKey(EQUAL_KEYS);
  }

  String nextLookupKey(String[] keys) {
    int i = lookupIndex + 1;
    if (i >= keys.length) {
      i = 0;
    }
    lookupIndex = i;
    return keys[i];
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

  // StringIndex + a parallel value array used as a FIXED (build-once, read-only) map. The keys are
  // known
  // up front, so they go in a StringIndex and the values in a plain array indexed by the slot that
  // Support.indexOf returns. There is no node, no Entry, and no per-lookup allocation -- a get is a
  // hash probe (with an interned == fast path) plus one array load. Pull the Data into your own
  // static finals (as below) so the hashes/names refs fold to constants -- same static-vs-instance
  // win SetBenchmark and KeyOfBenchmark measure. The values live in a parallel int[] -- no boxing,
  // the same primitive-value advantage TagMap.getInt has over a HashMap<String, Integer>, whose
  // value type structurally forces the box. The trade-off: it cannot change after construction.
  static final int[] STRING_INDEX_HASHES;
  static final String[] STRING_INDEX_NAMES;
  static final int[] STRING_INDEX_VALUES;

  static {
    StringIndex.Data data = StringIndex.Support.create(INSERTION_KEYS);
    int[] values = new int[data.names.length];
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      values[StringIndex.Support.indexOf(data.hashes, data.names, INSERTION_KEYS[i])] = i;
    }
    STRING_INDEX_HASHES = data.hashes;
    STRING_INDEX_NAMES = data.names;
    STRING_INDEX_VALUES = values;
  }

  @Benchmark
  public int get_stringIndexMap() {
    int slot =
        StringIndex.Support.indexOf(STRING_INDEX_HASHES, STRING_INDEX_NAMES, nextLookupKey());
    return slot < 0 ? -1 : STRING_INDEX_VALUES[slot];
  }

  @Benchmark
  public int get_stringIndexMap_sameKey() {
    int slot =
        StringIndex.Support.indexOf(
            STRING_INDEX_HASHES, STRING_INDEX_NAMES, nextLookupKey(INSERTION_KEYS));
    return slot < 0 ? -1 : STRING_INDEX_VALUES[slot];
  }
}

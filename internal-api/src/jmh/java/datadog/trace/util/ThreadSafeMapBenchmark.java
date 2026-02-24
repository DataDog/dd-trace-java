package datadog.trace.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 *
 *
 * <ul>
 *   Benchmark comparing different approaches to filling and reading a Map in a multi-thread
 *   context.
 *   <li>ConcurrentMap - only when there are simultaneously readers & writers in multiple threads
 *   <li>HashMap via volatile - preferred for background thread updates
 *   <li>synchronized HashMap - when simultaneous readers & writers are uncommon (e.g. tags)
 * </ul>
 *
 * <p>
 *
 * <p>In most situations in dd-java-agent, ConcurrentMaps are not necessarily needed and incur
 * additional overhead. ConcurrentMaps make sense when concurrent writers are likely.
 *
 * <p>If a Map can be created atomically in one thread and then stored into a volatile, that is the
 * preferred solution. For example, requesting an update from agent / API and then exposing to the
 * rest of the tracer via a global.
 *
 * <p>If a Map needs to be written in a thread-safe manner, but is primarily accessed from one
 * thread at a time, then a synchronized HashMap is usually the best option. <code>
 * MacBook M1 with 1 thread (Java 21)
 *
 * Benchmark                                            Mode  Cnt          Score          Error  Units
 * ThreadSafeMapBenchmark.create_concHashMap           thrpt    6    8081979.153 ±   261559.222  ops/s
 * ThreadSafeMapBenchmark.create_concSkipListMap       thrpt    6    2998832.124 ±   103708.038  ops/s
 * ThreadSafeMapBenchmark.create_hashMap               thrpt    6   24938311.610 ±   673725.902  ops/s
 * ThreadSafeMapBenchmark.create_hashMap_synchronized  thrpt    6    7971740.607 ±   121986.296  ops/s
 *
 * ThreadSafeMapBenchmark.get_concHashMap              thrpt    6  173942565.340 ± 12003493.448  ops/s
 * ThreadSafeMapBenchmark.get_concSkipListMap          thrpt    6   79230298.061 ± 13007895.765  ops/s
 * ThreadSafeMapBenchmark.get_hashMap_synchronized     thrpt    6   98056657.832 ±  3413815.061  ops/s
 * ThreadSafeMapBenchmark.get_hashMap_volatile         thrpt    6  210511753.596 ±  5017502.317  ops/s
 * </code> <code>
 * MacBook M1 with 8 threads (Java 21)
 *
 * Benchmark                                            Mode  Cnt          Score           Error  Units
 * ThreadSafeMapBenchmark.create_concHashMap           thrpt    6   58015351.219 ±   6201384.867  ops/s
 * ThreadSafeMapBenchmark.create_concSkipListMap       thrpt    6   19296105.790 ±   4516587.751  ops/s
 * ThreadSafeMapBenchmark.create_hashMap               thrpt    6  147917381.815 ±  22901897.589  ops/s
 * ThreadSafeMapBenchmark.create_hashMap_synchronized  thrpt    6   56466354.962 ±  13202034.783  ops/s
 *
 * ThreadSafeMapBenchmark.get_concHashMap              thrpt    6  849986442.797 ±  14499355.893  ops/s
 * ThreadSafeMapBenchmark.get_concSkipListMap          thrpt    6   26828246.629 ±   2772377.532  ops/s
 * ThreadSafeMapBenchmark.get_hashMap_synchronized     thrpt    6   20123419.604 ±   4858466.787  ops/s
 * ThreadSafeMapBenchmark.get_hashMap_volatile         thrpt    6  286024211.995 ± 114449056.603  ops/s
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class ThreadSafeMapBenchmark {
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

  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

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

  static void fill(Map<String, Integer> map) {
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      map.put(INSERTION_KEYS[i], i);
    }
  }

  static final HashMap<String, Integer> _create_hashMap() {
    HashMap<String, Integer> map = new HashMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public Map<String, Integer> create_hashMap() {
    return _create_hashMap();
  }

  static volatile HashMap<String, Integer> VOLATILE_HASH_MAP = _create_hashMap();

  @Benchmark
  public Integer get_hashMap_volatile() {
    Map<String, Integer> map = VOLATILE_HASH_MAP;
    return map.get(nextLookupKey());
  }

  static final Map<String, Integer> _create_hashMap_synchronized() {
    Map<String, Integer> map = Collections.synchronizedMap(new HashMap<>());
    fill(map);
    return map;
  }

  @Benchmark
  public Map<String, Integer> create_hashMap_synchronized() {
    return _create_hashMap_synchronized();
  }

  static final Map<String, Integer> SYNC_HASH_MAP = _create_hashMap_synchronized();

  @Benchmark
  public Integer get_hashMap_synchronized() {
    return SYNC_HASH_MAP.get(nextLookupKey());
  }

  static ConcurrentHashMap<String, Integer> _create_concHashMap() {
    ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public ConcurrentHashMap<String, Integer> create_concHashMap() {
    return _create_concHashMap();
  }

  static final ConcurrentHashMap<String, Integer> CONC_HASH_MAP = _create_concHashMap();

  @Benchmark
  public Integer get_concHashMap() {
    return CONC_HASH_MAP.get(nextLookupKey());
  }

  static ConcurrentSkipListMap<String, Integer> _create_concSkipListMap() {
    ConcurrentSkipListMap<String, Integer> map = new ConcurrentSkipListMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public ConcurrentSkipListMap<String, Integer> create_concSkipListMap() {
    return _create_concSkipListMap();
  }

  static final ConcurrentSkipListMap<String, Integer> CONC_SKIP_LIST_MAP =
      _create_concSkipListMap();

  @Benchmark
  public Integer get_concSkipListMap() {
    return CONC_SKIP_LIST_MAP.get(nextLookupKey());
  }
}

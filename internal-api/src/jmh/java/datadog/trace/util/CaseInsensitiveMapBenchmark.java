package datadog.trace.util;

import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
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
 *   Benchmark to illustrate the trade-offs around case-insensitive Map look-ups - using either...
 *   <li>(RECOMMENDED) TreeMap with Comparator of String::compareToIgnoreCase
 *   <li>HashMap with look-ups using String::to<X>Case
 * </ul>
 *
 * <p>For case-insensitive lookups, TreeMap map creation is consistently faster because it avoids
 * String::to<X>Case calls.
 *
 * <p>Despite calls to String::to<X>Case, HashMap lookups are faster in single threaded
 * microbenchmark by 50% but are worse when frequently called in a multi-threaded system.
 *
 * <p>With many threads, the extra allocation from calling String::to<X>Case leads to frequent GCs
 * which has adverse impacts on the whole system. <code>
 * MacBook M1 with 1 thread (Java 21)
 *
 * Benchmark                                     Mode  Cnt           Score         Error  Units
 * CaseInsensitiveMapBenchmark.create_hashMap   thrpt    6      994213.041 ±   15718.903  ops/s
 * CaseInsensitiveMapBenchmark.create_treeMap   thrpt    6     1522900.015 ±   21646.688  ops/s
 *
 * CaseInsensitiveMapBenchmark.get_hashMap      thrpt    6    69149862.293 ± 9168648.566  ops/s
 * CaseInsensitiveMapBenchmark.get_treeMap      thrpt    6    42796699.230 ± 9029447.805  ops/s
 * </code> <code>
 * MacBook M1 with 8 threads (Java 21)
 *
 * Benchmark                                     Mode  Cnt           Score          Error  Units
 * CaseInsensitiveMapBenchmark.create_hashMap   thrpt    6     6641003.483 ±   543210.409  ops/s
 * CaseInsensitiveMapBenchmark.create_treeMap   thrpt    6    10030191.764 ±  1308865.113  ops/s
 *
 * CaseInsensitiveMapBenchmark.get_hashMap      thrpt    6    38748031.837 ±  9012072.804  ops/s
 * CaseInsensitiveMapBenchmark.get_treeMap      thrpt    6   173495470.789 ± 27824904.999  ops/s
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class CaseInsensitiveMapBenchmark {
  static final String[] PREFIXES = {"foo", "bar", "baz", "quux"};

  static final int NUM_SUFFIXES = 4;

  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  static final String[] UPPER_PREFIXES =
      init(
          () -> {
            String[] upperPrefixes = new String[PREFIXES.length];
            for (int i = 0; i < PREFIXES.length; ++i) {
              upperPrefixes[i] = PREFIXES[i].toUpperCase();
            }
            return upperPrefixes;
          });

  static final String[] LOOKUP_KEYS =
      init(
          () -> {
            ThreadLocalRandom curRandom = ThreadLocalRandom.current();

            String[] keys = new String[32];
            for (int i = 0; i < keys.length; ++i) {
              int prefixIndex = curRandom.nextInt(PREFIXES.length);
              boolean toUpper = curRandom.nextBoolean();
              int suffixIndex = curRandom.nextInt(NUM_SUFFIXES + 1);

              String key = PREFIXES[prefixIndex] + "-" + suffixIndex;
              keys[i] = toUpper ? key.toUpperCase() : key.toLowerCase();
            }
            return keys;
          });

  static int sharedLookupIndex = 0;

  static String nextLookupKey() {
    int localIndex = ++sharedLookupIndex;
    if (localIndex >= LOOKUP_KEYS.length) {
      sharedLookupIndex = localIndex = 0;
    }
    return LOOKUP_KEYS[localIndex];
  }

  @Benchmark
  public void create_baseline(Blackhole blackhole) {
    for (int suffix = 0; suffix < NUM_SUFFIXES; ++suffix) {
      for (String prefix : PREFIXES) {
        blackhole.consume(prefix + "-" + suffix);
        blackhole.consume(Integer.valueOf(suffix));
      }
    }
    for (int suffix = 0; suffix < NUM_SUFFIXES; suffix += 2) {
      for (String prefix : UPPER_PREFIXES) {
        blackhole.consume(prefix + "-" + suffix);
        blackhole.consume(Integer.valueOf(suffix + 1));
      }
    }
  }

  @Benchmark
  public void lookup_baseline(Blackhole blackhole) {
    blackhole.consume(nextLookupKey());
  }

  @Benchmark
  public HashMap<String, Integer> create_hashMap() {
    return _create_hashMap();
  }

  static HashMap<String, Integer> _create_hashMap() {
    HashMap<String, Integer> map = new HashMap<>();
    for (int suffix = 0; suffix < NUM_SUFFIXES; ++suffix) {
      for (String prefix : PREFIXES) {
        map.put(
            (prefix + "-" + suffix).toLowerCase(),
            suffix); // arguable, but real caller probably doesn't know the case ahead-of-time
      }
    }
    for (int suffix = 0; suffix < NUM_SUFFIXES; suffix += 2) {
      for (String prefix : UPPER_PREFIXES) {
        map.put((prefix + "-" + suffix).toLowerCase(), suffix + 1);
      }
    }
    return map;
  }

  static final HashMap<String, Integer> HASH_MAP = _create_hashMap();

  @Benchmark
  public Integer lookup_hashMap() {
    // This benchmark is still "correct" in multi-threaded context,
    // Map is populated under the class initialization lock and not changed thereafter
    return HASH_MAP.get(nextLookupKey().toLowerCase());
  }

  @Benchmark
  public TreeMap<String, Integer> create_treeMap() {
    return _create_treeMap();
  }

  static TreeMap<String, Integer> _create_treeMap() {
    TreeMap<String, Integer> map = new TreeMap<>(String::compareToIgnoreCase);
    for (int suffix = 0; suffix < NUM_SUFFIXES; ++suffix) {
      for (String prefix : PREFIXES) {
        map.put(prefix + "-" + suffix, suffix);
      }
    }
    for (int suffix = 0; suffix < NUM_SUFFIXES; suffix += 2) {
      for (String prefix : UPPER_PREFIXES) {
        map.put(prefix + "-" + suffix, suffix + 1);
      }
    }
    return map;
  }

  static final TreeMap<String, Integer> TREE_MAP = _create_treeMap();

  @Benchmark
  public Integer lookup_treeMap() {
    // This benchmark is still "correct" in multi-threaded context,
    // Map is populated under the initial class initialization lock and not changed thereafter
    return TREE_MAP.get(nextLookupKey());
  }

  // TODO: Add ConcurrentSkipListMap & synchronized HashMap & TreeMap
}

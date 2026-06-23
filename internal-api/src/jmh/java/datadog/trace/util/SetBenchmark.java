package datadog.trace.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
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
 *   Benchmark showing possible ways to represent and check if a set includes an elememt...
 *   <li>(RECOMMENDED) HashSet - on par with TreeSet - idiomatic
 *   <li>(RECOMMENDED) TreeMap - on par with HashSet - better solution if custom comparator is
 *       needed (see CaseInsensitiveMapBenchmark)
 *   <li>array - slower than HashSet
 *   <li>sortedArray - slowest - slower than array for common case of small arrays
 * </ul>
 *
 * <code>
 * MacBook M1 - 8 threads - Java 21
 * 1/3 not found rate
 *
 * Benchmark                           Mode  Cnt           Score           Error  Units
 * SetBenchmark.contains_array        thrpt    6   645561886.327 ± 100781717.494  ops/s
 * SetBenchmark.contains_hashSet      thrpt    6  1536236680.235 ± 114966961.506  ops/s
 * SetBenchmark.contains_sortedArray  thrpt    6   571476939.441 ±  21334620.460  ops/s
 * SetBenchmark.contains_treeSet      thrpt    6  1557663759.411 ±  95343683.124  ops/s
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class SetBenchmark {
  static final String[] STRINGS =
      new String[] {
        "foo",
        "bar",
        "baz",
        "quux",
        "hello",
        "world",
        "service",
        "queryString",
        "lorem",
        "ipsum",
        "dolem",
        "sit"
      };

  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  static final String[] LOOKUPS =
      init(
          () -> {
            String[] lookups = Arrays.copyOf(STRINGS, STRINGS.length * 10);

            for (int i = 0; i < STRINGS.length; ++i) {
              lookups[STRINGS.length + i] = new String(STRINGS[i]);
            }

            // 2 / 3 of the key look-ups miss the set
            for (int i = STRINGS.length * 2; i < lookups.length; ++i) {
              lookups[i] = "dne-" + ThreadLocalRandom.current().nextInt();
            }

            Collections.shuffle(Arrays.asList(lookups));
            return lookups;
          });

  static int sharedLookupIndex = 0;

  static String nextString() {
    int localIndex = ++sharedLookupIndex;
    if (localIndex >= LOOKUPS.length) {
      sharedLookupIndex = localIndex = 0;
    }
    return LOOKUPS[localIndex];
  }

  static final String[] ARRAY = STRINGS;

  @Benchmark
  public boolean contains_array() {
    String needle = nextString();
    for (String str : ARRAY) {
      if (needle.equals(str)) return true;
    }
    return false;
  }

  static final String[] SORTED_ARRAY =
      init(
          () -> {
            String[] sorted = Arrays.copyOf(STRINGS, STRINGS.length);
            Arrays.sort(sorted);
            return sorted;
          });

  @Benchmark
  public boolean contains_sortedArray() {
    return (Arrays.binarySearch(SORTED_ARRAY, nextString()) != -1);
  }

  static final HashSet<String> HASH_SET = new HashSet<>(Arrays.asList(STRINGS));

  @Benchmark
  public boolean contains_hashSet() {
    return HASH_SET.contains(nextString());
  }

  static final TreeSet<String> TREE_SET = new TreeSet<>(Arrays.asList(STRINGS));

  @Benchmark
  public boolean contains_treeSet() {
    return HASH_SET.contains(nextString());
  }
}

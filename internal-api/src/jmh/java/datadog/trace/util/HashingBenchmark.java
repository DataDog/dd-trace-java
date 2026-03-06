package datadog.trace.util;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * In contrast to java.util.Objects.hash, datadog.util.HashingUtils.hash has overrides for different
 * parameter counts that allow most callers to avoid calling the var-arg version. This avoids the
 * common situation where the JIT's escape analysis is unable to elide the var-arg array allocation.
 *
 * <p>This results in 3-4x throughput, but more importantly no allocation as compared to GiBs / sec
 * with var-args. <code>
 * MacBook M1 using 8 threads/cores with -prof gc
 *
 * Benchmark                                           Mode  Cnt           Score           Error   Units
 *
 * HashingBenchmark.hash2                             thrpt    6  3365779949.250 ± 270198455.226   ops/s
 * HashingBenchmark.hash2:gc.alloc.rate               thrpt    6           0.001 ±         0.001  MB/sec
 *
 * HashingBenchmark.hash2_varargs                     thrpt    6  1194884232.767 ±  39724408.823   ops/s
 * HashingBenchmark.hash2_varargs:gc.alloc.rate       thrpt    6       27330.473 ±       909.029  MB/sec
 *
 *
 * HashingBenchmark.hash3                             thrpt    6  2314013984.714 ± 181952393.469   ops/s
 * HashingBenchmark.hash3:gc.alloc.rate               thrpt    6           0.001 ±         0.001  MB/sec
 *
 * HashingBenchmark.hash3_varags                      thrpt    6   869246242.250 ± 121680442.505   ops/s
 * HashingBenchmark.hash3_varags:gc.alloc.rate        thrpt    6       26514.569 ±      3709.819  MB/sec
 *
 *
 * HashingBenchmark.hash4                             thrpt    6  1866997193.226 ± 181198915.326   ops/s
 * HashingBenchmark.hash4:gc.alloc.rate               thrpt    6           0.001 ±         0.001  MB/sec
 *
 * HashingBenchmark.hash4_varargs                     thrpt    6   702697142.147 ±  24458612.481   ops/s
 * HashingBenchmark.hash4_varargs:gc.alloc.rate       thrpt    6       21437.996 ±       748.911  MB/sec
 *
 *
 * HashingBenchmark.hash5                             thrpt    6  1803117534.112 ± 242918817.144   ops/s
 * HashingBenchmark.hash5:gc.alloc.rate               thrpt    6           0.001 ±         0.001  MB/sec
 *
 * HashingBenchmark.hash5_varargs                     thrpt    6   579139583.196 ±  29525483.594   ops/s
 * HashingBenchmark.hash5_varargs:gc.alloc.rate       thrpt    6       22082.357 ±      1125.413  MB/sec
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class HashingBenchmark {
  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  // strings used in hashing are set up ahead of time, so that the only allocation is from var-args
  static String[] TEST_STRINGS =
      init(
          () -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();

            String[] strings = new String[1024];
            for (int i = 0; i < strings.length; ++i) {
              strings[i] = Double.toString(random.nextDouble());
            }
            return strings;
          });

  static {
    Thread updaterThread =
        new Thread(
            () -> {
              ThreadLocalRandom random = ThreadLocalRandom.current();

              while (!Thread.interrupted()) {
                str0 = TEST_STRINGS[random.nextInt(0, TEST_STRINGS.length)];
                str1 = TEST_STRINGS[random.nextInt(0, TEST_STRINGS.length)];
                str2 = TEST_STRINGS[random.nextInt(0, TEST_STRINGS.length)];
                str3 = TEST_STRINGS[random.nextInt(0, TEST_STRINGS.length)];
                str4 = TEST_STRINGS[random.nextInt(0, TEST_STRINGS.length)];
              }
            });
    updaterThread.setDaemon(true);
    updaterThread.start();
  }

  static String str0;
  static String str1;
  static String str2;
  static String str3;
  static String str4;

  @Benchmark
  public int hash2() {
    return datadog.trace.util.HashingUtils.hash(str0, str1);
  }

  @Benchmark
  public int hash2_varargs() {
    return java.util.Objects.hash(str0, str1);
  }

  @Benchmark
  public int hash3() {
    return datadog.trace.util.HashingUtils.hash(str0, str1, str2);
  }

  @Benchmark
  public int hash3_varags() {
    return java.util.Objects.hash(str0, str1, str2);
  }

  @Benchmark
  public int hash4() {
    return datadog.trace.util.HashingUtils.hash(str0, str1, str2, str3);
  }

  @Benchmark
  public int hash4_varargs() {
    return java.util.Objects.hash(str0, str1, str2, str3);
  }

  @Benchmark
  public int hash5() {
    return datadog.trace.util.HashingUtils.hash(str0, str1, str2, str3, str4);
  }

  @Benchmark
  public int hash5_varargs() {
    return java.util.Objects.hash(str0, str1, str2, str3, str4);
  }
}

package datadog.trace.util;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Strings.substring has 5x throughput. This is primarily achieved through less allocation.
 *
 * <p>NOTE: The higher allocation rate is misleading because 5x the work was performed. After
 * accounting for the 5x throughput difference, the actual allocation rate is 0.25x that of
 * String.substring or String.subSequence / SubSequence.of. <code>
 * Benchmark                                                          Mode  Cnt          Score          Error   Units
 * StringSubSequenceBenchmark.string_subSequence                     thrpt    6  140369998.493 ±  4387855.861   ops/s
 * StringSubSequenceBenchmark.string_subSequence:gc.alloc.rate       thrpt    6      88880.463 ±     2778.032  MB/sec
 *
 * StringSubSequenceBenchmark.string_substring                       thrpt    6  136916708.207 ± 12299226.575   ops/s
 * StringSubSequenceBenchmark.string_substring:gc.alloc.rate         thrpt    6      86689.852 ±     7777.642  MB/sec
 *
 * StringSubSequenceBenchmark.subSequence                            thrpt    6  679669385.260 ±  7194043.619   ops/s
 * StringSubSequenceBenchmark.subSequence:gc.alloc.rate              thrpt    6     103702.745 ±     1095.741  MB/sec
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class StringSubSequenceBenchmark {
  static final String LOREM_IPSUM =
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";

  @Benchmark
  public void string_substring(Blackhole bh) {
    String str = LOREM_IPSUM;
    int len = str.length();

    for (int i = 0; i < str.length(); i += 100) {
      bh.consume(str.substring(i, Math.min(i + 100, len)));
    }
  }

  @Benchmark
  public void string_subSequence(Blackhole bh) {
    String str = LOREM_IPSUM;
    int len = str.length();

    for (int i = 0; i < str.length(); i += 100) {
      bh.consume(str.subSequence(i, Math.min(i + 100, len)));
    }
  }

  @Benchmark
  public void subSequence(Blackhole bh) {
    String str = LOREM_IPSUM;
    int len = str.length();

    for (int i = 0; i < str.length(); i += 100) {
      bh.consume(SubSequence.of(str, i, Math.min(i + 100, len)));
    }
  }
}

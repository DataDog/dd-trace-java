package datadog.trace.util;

import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Strings.split is generally faster for String processing, since it create SubSequences that are
 * view into the backing String rather than new String objects.
 *
 * <p>Benchmark (testStr) Mode Cnt Score Error Units StringSplitBenchmark.pattern_split EMPTY thrpt
 * 6 291274421.621 ± 14834420.899 ops/s StringSplitBenchmark.string_split EMPTY thrpt 6
 * 1035461179.368 ± 60212686.921 ops/s StringSplitBenchmark.strings_split EMPTY thrpt 6
 * 8161781738.019 ± 178530888.497 ops/s
 *
 * <p>StringSplitBenchmark.pattern_split TRIVIAL thrpt 6 83982270.075 ± 10250565.633 ops/s
 * StringSplitBenchmark.string_split TRIVIAL thrpt 6 848615850.339 ± 42453569.634 ops/s
 * StringSplitBenchmark.strings_split TRIVIAL thrpt 6 1765290890.948 ± 160053487.111 ops/s
 *
 * <p>StringSplitBenchmark.pattern_split SMALL thrpt 6 27383819.756 ± 5454020.100 ops/s
 * StringSplitBenchmark.string_split SMALL thrpt 6 149047480.037 ± 6124271.615 ops/s
 * StringSplitBenchmark.strings_split SMALL thrpt 6 564058097.162 ± 49305418.971 ops/s
 *
 * <p>StringSplitBenchmark.pattern_split MEDIUM thrpt 6 14879131.729 ± 1981850.920 ops/s
 * StringSplitBenchmark.string_split MEDIUM thrpt 6 51237769.598 ± 1808521.138 ops/s
 * StringSplitBenchmark.strings_split MEDIUM thrpt 6 176976970.705 ± 6813886.658 ops/s
 *
 * <p>StringSplitBenchmark.pattern_split LARGE thrpt 6 482340.838 ± 24903.187 ops/s
 * StringSplitBenchmark.string_split LARGE thrpt 6 2460212.879 ± 86911.652 ops/s
 * StringSplitBenchmark.strings_split LARGE thrpt 6 4023658.103 ± 30305.699 ops/s
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Benchmark)
public class StringSplitBenchmark {
  public enum TestString {
    EMPTY(""),
    TRIVIAL("app_key=1111"),
    SMALL("app_key=1111&foo=bar&baz=quux"),
    MEDIUM(repeat("app_key=1111", '&', 100)),
    LARGE(repeat("app_key=1111&application_key=2222&token=0894-4832", '&', 4096));

    final String str;

    TestString(String str) {
      this.str = str;
    }
  };

  @Param TestString testStr;

  static final String repeat(String repeat, char separator, int length) {
    StringBuilder builder = new StringBuilder(length);
    builder.append(repeat);
    while (builder.length() + repeat.length() + 1 < length) {
      builder.append(separator).append(repeat);
    }
    return builder.toString();
  }

  @Benchmark
  public void string_split(Blackhole bh) {
    for (String substr : this.testStr.str.split("\\&")) {
      bh.consume(substr);
    }
  }

  static final Pattern PATTERN = Pattern.compile("\\&");

  @Benchmark
  public void pattern_split(Blackhole bh) {
    for (String str : PATTERN.split(this.testStr.str)) {
      bh.consume(str);
    }
  }

  @Benchmark
  public void strings_split(Blackhole bh) {
    for (SubSequence subSeq : Strings.split(this.testStr.str, '&')) {
      bh.consume(subSeq);
    }
  }
}

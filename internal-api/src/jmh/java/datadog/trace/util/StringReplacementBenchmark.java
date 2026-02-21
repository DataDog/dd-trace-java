package datadog.trace.util;

import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * For simple replacements, Strings.replaceAll out performs String.replaceAll and
 * regex.Matcher.replaceAll by 3x. Strings.replaceAll also requires less allocation.
 *
 * <p>When pattern matching is needed, compiling the regex to Pattern slightly improves overhead,
 * but dramatically reduces memory allocation to 1/4x of String.replaceAll. <code>
 * MacBook M1 with 8 threads (Java 21)
 *
 * Benchmark                                                          Mode  Cnt         Score         Error   Units
 * StringReplacementBenchmark.regex_replaceAll                       thrpt    6  13795837.811 ± 3635087.691   ops/s
 * StringReplacementBenchmark.regex_replaceAll:gc.alloc.rate         thrpt    6      3988.955 ±    1148.316  MB/sec
 *
 * StringReplacementBenchmark.string_replaceAll                      thrpt    6  14611046.391 ± 4865682.875   ops/s
 * StringReplacementBenchmark.string_replaceAll:gc.alloc.rate        thrpt    6     11391.346 ±    3790.917  MB/sec
 *
 * StringReplacementBenchmark.strings_replaceAll                     thrpt    6  39514695.575 ± 7169844.210   ops/s
 * StringReplacementBenchmark.strings_replaceAll:gc.alloc.rate       thrpt    6      2777.083 ±     506.909  MB/sec
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
public class StringReplacementBenchmark {
  static final String[] INPUTS = {
    "foo",
    "baz",
    "foobar",
    "foobaz",
    "foo=baz",
    "bar=foo",
    "foo=foo&bar=foo",
    "lorem ipsum",
    "datadog"
  };

  static int sharedInputIndex = 0;

  static String nextInput() {
    int localIndex = ++sharedInputIndex;
    if (localIndex >= INPUTS.length) {
      sharedInputIndex = localIndex = 0;
    }
    return INPUTS[localIndex];
  }

  @Benchmark
  public String string_replaceAll() {
    return _string_replaceAll(nextInput());
  }

  static String _string_replaceAll(String input) {
    // Underneath, this does Pattern.compile("foo").matcher(str).replaceAll()
    return input.replaceAll("foo", "*redacted*");
  }

  static final Pattern REGEX_COMPILED = Pattern.compile("foo");

  @Benchmark
  public String regex_replaceAll() {
    return _regex_replaceAll(nextInput());
  }

  static String _regex_replaceAll(String input) {
    return REGEX_COMPILED.matcher(input).replaceAll("*redcated*");
  }

  @Benchmark
  public String strings_replaceAll() {
    return _strings_replaceAll(nextInput());
  }

  static String _strings_replaceAll(String input) {
    return Strings.replaceAll(input, "foo", "*redacted*");
  }
}

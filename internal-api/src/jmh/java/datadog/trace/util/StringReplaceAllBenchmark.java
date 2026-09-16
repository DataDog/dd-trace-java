package datadog.trace.util;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * <p>For simple replacements, Strings.replaceAll is recommened.
 *
 * <p>
 * For simple replacements, Strings.replaceAll or String.replace out performs the regex based
 * methods String.replaceAll and regex.Matcher.replaceAll by 3x in terms of throughput.
 *
 * <p>String.replace and Strings.replaceAll also require less allocation.
 *
 * <p>Strings.replaceAll out performs String.replace by 1.2x in terms of throughput,
 * but results may vary depending on the JVM version being used.
 *
 * <p>When pattern matching is needed, compiling the regex to Pattern slightly improves overhead,
 * but dramatically reduces memory allocation to 1/4x of String.replaceAll. <code>
 * MacBook M1 with 8 threads (Java 21)
 *
 * <code>
 * MacBook M1 - 8 Threads - Java 21
 *
 * StringReplaceAllBenchmark.regex_replaceAll                       thrpt    6  15500559.098 ± 8640183.754   ops/s
 * StringReplaceAllBenchmark.regex_replaceAll:gc.alloc.rate         thrpt    6      4516.464 ±    2561.063  MB/sec
 *
 * StringReplaceAllBenchmark.string_replace                         thrpt    6  35429131.963 ± 3203548.932   ops/s
 * StringReplaceAllBenchmark.string_replace:gc.alloc.rate           thrpt    6      3185.108 ±     152.601  MB/sec
 *
 * StringReplaceAllBenchmark.string_replaceAll                      thrpt    6  14253964.929 ± 4060225.866   ops/s
 * StringReplaceAllBenchmark.string_replaceAll:gc.alloc.rate        thrpt    6     11114.939 ±    3129.891  MB/sec
 *
 * StringReplaceAllBenchmark.strings_replaceAll                     thrpt    6  43789250.524 ± 1910948.420   ops/s
 * StringReplaceAllBenchmark.strings_replaceAll:gc.alloc.rate       thrpt    6      3079.973 ±     134.617  MB/sec
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@SuppressForbidden
public class StringReplaceAllBenchmark {
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

  @Benchmark
  public String string_replace() {
    return _string_replace(nextInput());
  }

  static String _string_replace(String input) {
    return input.replace("foo", "*redacted*");
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

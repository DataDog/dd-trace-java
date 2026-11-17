package com.datadog.openfeature.internal.ufc;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares {@code java.util.regex} {@code find()}, which the evaluator used before, with {@link
 * RegexMatcher}, for each matcher tier. Each pattern shape comes from real UFC targeting rules.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
public class RegexMatcherBenchmark {
  /** The pattern shape. */
  @Param({"literal-suffix", "wildcard-alternation", "char-class", "complex", "word-boundary"})
  public String shape;

  /** A matching input, a short input that does not match, or a 2,000-character miss. */
  @Param({"hit", "miss", "long-miss"})
  public String input;

  private Pattern jdk;
  private RegexMatcher linear;
  private String value;

  @Setup
  public void setup() {
    final String pattern;
    final String hit;
    final String miss;
    switch (shape) {
      case "literal-suffix":
        pattern = "@example\\.com$";
        hit = "jane.doe@example.com";
        miss = "jane.doe@example.org";
        break;
      case "wildcard-alternation":
        pattern = ".*@(site-a|site-b)\\.example$";
        hit = "jane.doe@site-b.example";
        miss = "jane.doe@site-c.example";
        break;
      case "char-class":
        pattern = "^[a-zA-Z0-9._%+-]+@site\\.test$";
        hit = "jane.doe+x@site.test";
        miss = "jane.doe+x@other.test";
        break;
      case "complex":
        pattern = "^u[0-9]{1,6}@(?:alpha|bravo|charlie)\\.(?:io|net)$";
        hit = "u12345@bravo.net";
        miss = "u12345@delta.net";
        break;
      case "word-boundary":
        pattern = "\\bma\u00f1ana\\b";
        hit = "llega ma\u00f1ana temprano";
        miss = "llega pronto";
        break;
      default:
        throw new IllegalArgumentException(shape);
    }
    jdk = Pattern.compile(pattern);
    linear = RegexMatcher.compile(pattern);
    switch (input) {
      case "hit":
        value = hit;
        break;
      case "miss":
        value = miss;
        break;
      default:
        final StringBuilder s = new StringBuilder(2_000 + miss.length());
        for (int i = 0; i < 2_000; i++) {
          s.append('a');
        }
        value = s.append(miss).toString();
        break;
    }
  }

  @Benchmark
  public boolean javaUtilRegex() {
    return jdk.matcher(value).find();
  }

  @Benchmark
  public boolean regexMatcher() {
    return linear.matches(value);
  }
}

package datadog.trace.bootstrap.jit;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Each optimization as a pair: the JDK method, and the helper that replaces it at call sites.
 * Inputs are short, typical config/header-sized strings.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class StringRewritesBenchmark {
  String words = "alpha beta  gamma\tdelta epsilon";
  String csv = "alpha , beta,gamma ,  delta, epsilon";
  String colons = "alpha::beta::gamma::delta";
  String dotted = "com.example.service.handler.Endpoint";
  String token = "abc123";
  String mixed = "a1;b2,c3;d4,e5";

  @Benchmark
  public String[] split_whitespace_jdk() {
    return words.split("\\s+");
  }

  @Benchmark
  public String[] split_whitespace_helper() {
    return StringRewrites.split(words, "\\s+");
  }

  @Benchmark
  public String[] split_paddedComma_jdk() {
    return csv.split("\\s*,\\s*");
  }

  @Benchmark
  public String[] split_paddedComma_helper() {
    return StringRewrites.split(csv, "\\s*,\\s*");
  }

  @Benchmark
  public String[] split_literal_jdk() {
    return colons.split("::");
  }

  @Benchmark
  public String[] split_literal_helper() {
    return StringRewrites.split(colons, "::");
  }

  @Benchmark
  public String[] split_regex_jdk() {
    return mixed.split("[,;]");
  }

  @Benchmark
  public String[] split_regex_helper() {
    return StringRewrites.split(mixed, "[,;]");
  }

  @Benchmark
  public String replaceAll_literal_jdk() {
    return dotted.replaceAll("\\.", "/");
  }

  @Benchmark
  public String replaceAll_literal_helper() {
    return StringRewrites.replaceAll(dotted, "\\.", "/");
  }

  @Benchmark
  public String replaceAll_regex_jdk() {
    return dotted.replaceAll("[aeiou]", "_");
  }

  @Benchmark
  public String replaceAll_regex_helper() {
    return StringRewrites.replaceAll(dotted, "[aeiou]", "_");
  }

  @Benchmark
  public String replaceFirst_literal_jdk() {
    return colons.replaceFirst("::", "=");
  }

  @Benchmark
  public String replaceFirst_literal_helper() {
    return StringRewrites.replaceFirst(colons, "::", "=");
  }

  @Benchmark
  public boolean matches_jdk() {
    return token.matches("[a-z]+\\d+");
  }

  @Benchmark
  public boolean matches_helper() {
    return StringRewrites.matches(token, "[a-z]+\\d+");
  }

  @Benchmark
  public boolean patternMatches_jdk() {
    return Pattern.matches("[a-z]+\\d+", token);
  }

  @Benchmark
  public boolean patternMatches_helper() {
    return StringRewrites.patternMatches("[a-z]+\\d+", token);
  }
}

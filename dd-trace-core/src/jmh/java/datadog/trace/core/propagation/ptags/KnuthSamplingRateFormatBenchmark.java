package datadog.trace.core.propagation.ptags;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.core.propagation.PropagationTags;
import java.util.Locale;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for formatting the Knuth sampling rate (_dd.p.ksr tag value).
 *
 * <p>The format requirement is %.6g semantics: 6 significant figures, no trailing zeros, using
 * fixed notation for values in [1e-4, 1] and scientific notation for smaller values.
 *
 * <p>Run with:
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmhJar
 *   java -jar dd-trace-core/build/libs/dd-trace-core-*-jmh.jar KnuthSamplingRateFormatBenchmark
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 10, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class KnuthSamplingRateFormatBenchmark {

  /**
   * Representative sampling rates. Most real-world rates are in [0.001, 1.0]. The 0.0001 value
   * exercises the edge of the fixed-notation range.
   */
  @Param({"0.5", "0.1", "0.01", "0.001", "0.0001", "0.123456789", "0.999999"})
  double rate;

  PTagsFactory.PTags ptags;

  @Setup(Level.Trial)
  public void setUp() {
    ptags = (PTagsFactory.PTags) PropagationTags.factory().empty();
    ptags.updateKnuthSamplingRate(rate);
  }

  /** Baseline: old implementation using String.format + substring trimming. */
  @Benchmark
  public void stringFormat(Blackhole bh) {
    bh.consume(stringFormatImpl(rate));
  }

  /** Custom formatter: char-array arithmetic, no Formatter allocation. */
  @Benchmark
  public void customFormat(Blackhole bh) {
    bh.consume(PTagsFactory.PTags.formatKnuthSamplingRate(rate));
  }

  /** Cached TagValue: the full getKnuthSamplingRateTagValue() hot-path after caching. */
  @Benchmark
  public void cachedTagValue(Blackhole bh) {
    bh.consume(ptags.getKnuthSamplingRateTagValue());
  }

  // ---- old implementation for comparison ----

  static String stringFormatImpl(double rate) {
    String formatted = String.format(Locale.ROOT, "%.6g", rate);
    int dotIndex = formatted.indexOf('.');
    if (dotIndex >= 0) {
      int end = formatted.length();
      while (end > dotIndex + 1 && formatted.charAt(end - 1) == '0') {
        end--;
      }
      if (formatted.charAt(end - 1) == '.') {
        end--;
      }
      formatted = formatted.substring(0, end);
    }
    return formatted;
  }
}

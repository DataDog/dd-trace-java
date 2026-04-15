package datadog.trace.core.propagation.ptags;

import static java.util.concurrent.TimeUnit.SECONDS;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for TagValue cache operations (hash + compare) with both DD and W3C encodings.
 *
 * <p>The hot path is {@code TagValue.from(Encoding, CharSequence, start, end)} which calls {@code
 * hashDD}/{@code hashW3C} and {@code compareDD}/{@code compareW3C} via the {@link
 * datadog.trace.api.cache.DDPartialKeyCache}.
 *
 * <p>Run with:
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmhJar
 *   java -jar dd-trace-core/build/libs/dd-trace-core-*-jmh.jar TagValueCacheBenchmark -prof gc
 * </pre>
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Threads(1)
@Fork(value = 1)
public class TagValueCacheBenchmark {

  @Param({"short", "medium", "long"})
  String valueLength;

  private String ddValue;
  private String w3cValue;
  private String ddValueWithSpecialChars;
  private String w3cValueWithSpecialChars;

  // Wrapper strings to exercise substring (start, end) paths
  private String ddWrapped;
  private int ddWrappedStart;
  private int ddWrappedEnd;
  private String w3cWrapped;
  private int w3cWrappedStart;
  private int w3cWrappedEnd;

  @Setup(Level.Trial)
  public void setUp() {
    switch (valueLength) {
      case "short":
        ddValue = "-4";
        w3cValue = "-4";
        ddValueWithSpecialChars = "foo=bar";
        w3cValueWithSpecialChars = "foo~bar";
        break;
      case "medium":
        ddValue = "934086a686-4";
        w3cValue = "934086a686-4";
        ddValueWithSpecialChars = "934086a686=4";
        w3cValueWithSpecialChars = "934086a686~4";
        break;
      case "long":
        ddValue = "934086a686b7cc57e204aa914002ab6d-4";
        w3cValue = "934086a686b7cc57e204aa914002ab6d-4";
        ddValueWithSpecialChars = "934086a686b7cc57=204aa914002ab6d-4";
        w3cValueWithSpecialChars = "934086a686b7cc57~204aa914002ab6d-4";
        break;
      default:
        throw new IllegalArgumentException("Unknown valueLength: " + valueLength);
    }
    // Pre-populate cache so lookups are hits
    TagValue.from(TagElement.Encoding.DATADOG, ddValue);
    TagValue.from(TagElement.Encoding.W3C, w3cValue);
    TagValue.from(TagElement.Encoding.DATADOG, ddValueWithSpecialChars);
    TagValue.from(TagElement.Encoding.W3C, w3cValueWithSpecialChars);

    // Create wrapped versions for substring lookups
    ddWrapped = "prefix" + ddValue + "suffix";
    ddWrappedStart = 6;
    ddWrappedEnd = 6 + ddValue.length();
    w3cWrapped = "prefix" + w3cValue + "suffix";
    w3cWrappedStart = 6;
    w3cWrappedEnd = 6 + w3cValue.length();
  }

  /** DD-encoded cache hit (hash + compare). */
  @Benchmark
  public void fromDD(Blackhole bh) {
    bh.consume(TagValue.from(TagElement.Encoding.DATADOG, ddValue));
  }

  /** W3C-encoded cache hit (hash + compare). */
  @Benchmark
  public void fromW3C(Blackhole bh) {
    bh.consume(TagValue.from(TagElement.Encoding.W3C, w3cValue));
  }

  /** DD-encoded with special chars that need conversion. */
  @Benchmark
  public void fromDDSpecialChars(Blackhole bh) {
    bh.consume(TagValue.from(TagElement.Encoding.DATADOG, ddValueWithSpecialChars));
  }

  /** W3C-encoded with special chars that need conversion. */
  @Benchmark
  public void fromW3CSpecialChars(Blackhole bh) {
    bh.consume(TagValue.from(TagElement.Encoding.W3C, w3cValueWithSpecialChars));
  }

  /** DD-encoded substring cache hit (exercises start/end path). */
  @Benchmark
  public void fromDDSubstring(Blackhole bh) {
    bh.consume(TagValue.from(TagElement.Encoding.DATADOG, ddWrapped, ddWrappedStart, ddWrappedEnd));
  }

  /** W3C-encoded substring cache hit (exercises start/end path). */
  @Benchmark
  public void fromW3CSubstring(Blackhole bh) {
    bh.consume(TagValue.from(TagElement.Encoding.W3C, w3cWrapped, w3cWrappedStart, w3cWrappedEnd));
  }
}

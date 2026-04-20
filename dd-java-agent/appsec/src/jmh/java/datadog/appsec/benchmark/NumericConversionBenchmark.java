package datadog.appsec.benchmark;

import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.internal.TraceSegment;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark for numeric attribute conversion performance (issue #10494).
 *
 * <p>Tests the optimization that replaces exception-driven parsing with fast-path validation to
 * avoid NumberFormatException overhead on invalid inputs.
 *
 * <p>Expected results: - Valid numeric inputs: no regression (~100-200ns/op) - Invalid inputs:
 * 10-100x improvement (from ~1000ns+ with exceptions to <100ns without) - Empty/whitespace: fast
 * rejection (<50ns/op)
 */
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3)
public class NumericConversionBenchmark {

  static {
    BenchmarkUtil.disableLogging();
  }

  private AppSecRequestContext context;
  private TraceSegment mockTraceSegment;

  @Setup(Level.Iteration)
  public void setUp() {
    context = new AppSecRequestContext();
    // Use NoOp TraceSegment to minimize overhead
    mockTraceSegment = TraceSegment.NoOp.INSTANCE;
  }

  @Benchmark
  public void validInteger(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "42")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void validIntegerNegative(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "-12345")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void validIntegerWithSign(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "+999")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void validDecimal(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "3.14")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void validDecimalNegative(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "-99.5")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void validLargeNumber(Blackhole blackhole) {
    context.reportDerivatives(
        singletonMap("test_attr", singletonMap("value", "9223372036854775807")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  // Invalid inputs - these should show the biggest improvement (no exceptions thrown)
  @Benchmark
  public void invalidAlphabetic(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "not_a_number")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void invalidAlphanumeric(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "12x34")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void invalidMultipleDecimals(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "3.14.15")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void invalidHexFormat(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "0x10")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void invalidScientific(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "1e10")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void invalidSpecialChars(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "$100")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  // Whitespace handling - now works correctly after optimization (issue #10494)
  @Benchmark
  public void whitespaceLeading(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", " 42")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void whitespaceTrailing(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "42 ")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void whitespaceBoth(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", " 42 ")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void whitespaceTabNewline(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "\t100\n")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  // Empty/null - fast rejection
  @Benchmark
  public void emptyString(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void whitespaceOnly(Blackhole blackhole) {
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "   ")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  // Overflow cases - should handle gracefully without exceptions
  @Benchmark
  public void overflowLongMax(Blackhole blackhole) {
    context.reportDerivatives(
        singletonMap("test_attr", singletonMap("value", "9223372036854775808")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  @Benchmark
  public void overflowVeryLarge(Blackhole blackhole) {
    context.reportDerivatives(
        singletonMap("test_attr", singletonMap("value", "99999999999999999999999")));
    boolean result = context.commitDerivatives(mockTraceSegment);
    blackhole.consume(result);
  }

  // Mixed workload - realistic scenario with 80% invalid, 20% valid
  @Benchmark
  public void mixedWorkload(Blackhole blackhole) {
    // Invalid (80%)
    context.reportDerivatives(singletonMap("attr1", singletonMap("value", "invalid")));
    blackhole.consume(context.commitDerivatives(mockTraceSegment));

    context.reportDerivatives(singletonMap("attr2", singletonMap("value", "abc123")));
    blackhole.consume(context.commitDerivatives(mockTraceSegment));

    context.reportDerivatives(singletonMap("attr3", singletonMap("value", "0x10")));
    blackhole.consume(context.commitDerivatives(mockTraceSegment));

    context.reportDerivatives(singletonMap("attr4", singletonMap("value", "")));
    blackhole.consume(context.commitDerivatives(mockTraceSegment));

    // Valid (20%)
    context.reportDerivatives(singletonMap("attr5", singletonMap("value", "42")));
    blackhole.consume(context.commitDerivatives(mockTraceSegment));
  }
}

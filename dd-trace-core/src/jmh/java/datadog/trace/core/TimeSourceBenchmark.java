package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.DDTraceId;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark of time source and PendingTrace time operations.
 *
 * <p>Measures the overhead of {@link PendingTrace#getCurrentTimeNano()}, which is called on every
 * span start and finish. The key cost is a volatile write to {@code lastReferenced} plus the clock
 * synchronization math in {@link CoreTracer#getTimeWithNanoTicks(long)}.
 *
 * <p>Baselines ({@link #systemNanoTime()} and {@link #systemCurrentTimeMillis()}) allow isolating
 * the overhead introduced by the volatile write and clock-sync calculation on top of the raw OS
 * clock calls.
 *
 * <p>NOTE: This is a multi-threaded benchmark; single-threaded runs do not accurately reflect the
 * volatile-write contention that occurs in production. Use {@code -t 1} for a single-threaded run.
 */
@State(Scope.Thread)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class TimeSourceBenchmark {

  // One shared tracer across all threads.
  static final CoreTracer TRACER = CoreTracer.builder().build();

  // Per-thread state: each thread holds its own PendingTrace (via an unfinished span) so that the
  // volatile write to PendingTrace.lastReferenced does not serialize all threads on one object.
  private PendingTrace pendingTrace;

  @Setup(Level.Trial)
  public void setup() {
    // Create a TraceCollector (PendingTrace) and keep it alive by not finishing the span.
    // CoreTracer.createTraceCollector is public and intended for benchmark use.
    TraceCollector collector = TRACER.createTraceCollector(DDTraceId.ONE);
    // PendingTrace is the only implementation returned by createTraceCollector.
    pendingTrace = (PendingTrace) collector;
  }

  @TearDown(Level.Trial)
  public void teardown() {
    pendingTrace = null;
  }

  /**
   * Measures {@link PendingTrace#getCurrentTimeNano()}: a {@link
   * datadog.trace.api.time.TimeSource#getNanoTicks()} call, a volatile write to {@code
   * lastReferenced}, and the clock-sync calculation in {@link
   * CoreTracer#getTimeWithNanoTicks(long)}.
   */
  @Benchmark
  public long getCurrentTimeNano() {
    return pendingTrace.getCurrentTimeNano();
  }

  /** Baseline: raw {@link System#nanoTime()} with no additional work. */
  @Benchmark
  public long systemNanoTime() {
    return System.nanoTime();
  }

  /** Baseline: raw {@link System#currentTimeMillis()} with no additional work. */
  @Benchmark
  public long systemCurrentTimeMillis() {
    return System.currentTimeMillis();
  }

  /**
   * Measures {@link CoreTracer#getTimeWithNanoTicks(long)}: the clock synchronization calculation
   * on top of a {@link System#nanoTime()} call. Does not perform the volatile write to {@code
   * lastReferenced}.
   */
  @Benchmark
  public long traceGetTimeWithNanoTicks() {
    return TRACER.getTimeWithNanoTicks(System.nanoTime());
  }
}

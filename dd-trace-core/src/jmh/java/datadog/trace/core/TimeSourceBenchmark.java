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

@State(Scope.Thread)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class TimeSourceBenchmark {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  private PendingTrace pendingTrace;

  @Setup(Level.Trial)
  public void setup() {
    TraceCollector collector = TRACER.createTraceCollector(DDTraceId.ONE);
    pendingTrace = (PendingTrace) collector;
  }

  @TearDown(Level.Trial)
  public void teardown() {
    pendingTrace = null;
  }

  @Benchmark
  public long getCurrentTimeNano() {
    return pendingTrace.getCurrentTimeNano();
  }

  @Benchmark
  public long systemNanoTime() {
    return System.nanoTime();
  }

  @Benchmark
  public long systemCurrentTimeMillis() {
    return System.currentTimeMillis();
  }

  @Benchmark
  public long traceGetTimeWithNanoTicks() {
    return TRACER.getTimeWithNanoTicks(System.nanoTime());
  }

  /**
   * Measures a full span start + finish cycle, exercising both the {@code rootSpan} CAS guard in
   * {@link PendingTrace#registerSpan} and the {@code lazySet} of {@code lastReferenced} in {@link
   * PendingTrace#getCurrentTimeNano}.
   */
  @Benchmark
  public void startAndFinishSpan() {
    TRACER.startSpan("benchmark", "op").finish();
  }

  @State(Scope.Benchmark)
  public static class SharedState {
    PendingTrace sharedTrace;

    @Setup(Level.Trial)
    public void setup() {
      TraceCollector collector = TRACER.createTraceCollector(DDTraceId.ONE);
      sharedTrace = (PendingTrace) collector;
    }

    @TearDown(Level.Trial)
    public void teardown() {
      sharedTrace = null;
    }
  }

  /**
   * Measures {@link PendingTrace#getCurrentTimeNano()} under cross-thread contention on a single
   * shared {@code PendingTrace}. All threads write to the same {@code lastReferenced} field,
   * demonstrating the benefit of {@code lazySet} over a volatile store under contention.
   */
  @Benchmark
  public long getCurrentTimeNano_contended(SharedState shared) {
    return shared.sharedTrace.getCurrentTimeNano();
  }
}

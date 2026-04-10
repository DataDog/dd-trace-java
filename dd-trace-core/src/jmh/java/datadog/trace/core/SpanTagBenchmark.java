package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark of DDSpan tag setting and getting operations.
 *
 * <p>Tag operations run on every instrumented method call, so throughput here directly affects
 * application overhead. The span is per-thread to avoid measuring lock contention on unsafeTags
 * (DDSpanContext.setTag synchronizes on that field), giving a cleaner view of the tag-path cost
 * itself.
 *
 * <p>NOTE: This is a multi-threaded benchmark; single threaded benchmarks don't accurately reflect
 * some of the optimizations.
 *
 * <p>Use -t 1, if you'd like to do a single threaded run.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class SpanTagBenchmark {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  /**
   * Per-thread span state. Each thread owns its own span so benchmarks measure tag operations
   * without cross-thread contention on the span's internal lock.
   */
  @State(Scope.Thread)
  public static class ThreadState {
    AgentSpan span;

    @Setup(Level.Trial)
    public void init() {
      // Start a span that lives for the entire benchmark trial. Spans are intentionally not
      // finished so that tag operations keep running against a live span context.
      span = TRACER.startSpan("benchmark", "operation");
    }
  }

  @Benchmark
  public AgentSpan setStringTag(ThreadState state) {
    return state.span.setTag("http.method", "GET");
  }

  @Benchmark
  public AgentSpan setBooleanTag(ThreadState state) {
    return state.span.setTag("error", true);
  }

  @Benchmark
  public AgentSpan setIntTag(ThreadState state) {
    return state.span.setTag("http.status_code", 200);
  }

  @Benchmark
  public AgentSpan setLongTag(ThreadState state) {
    return state.span.setTag("db.row_count", 42L);
  }

  @Benchmark
  public AgentSpan setDoubleTag(ThreadState state) {
    return state.span.setTag("duration.ms", 1.5);
  }

  @Benchmark
  public MutableSpan setOperationName(ThreadState state) {
    return state.span.setOperationName("http.request");
  }

  @Benchmark
  public AgentSpan setResourceName(ThreadState state) {
    return state.span.setResourceName("GET /api/v1/users");
  }

  @Benchmark
  public void getTag(ThreadState state, Blackhole bh) {
    bh.consume(state.span.getTag("http.method"));
  }
}

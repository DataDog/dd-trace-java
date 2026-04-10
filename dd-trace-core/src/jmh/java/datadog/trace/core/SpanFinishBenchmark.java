package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks of span finish operations and the trace collection path.
 *
 * <p>These operations are on the hot path — called when every span completes. The benchmarks cover:
 *
 * <ul>
 *   <li>{@link #finishSpan()} — finish a single span (AtomicLong CAS, metrics, PendingTrace ref
 *       count decrement)
 *   <li>{@link #fullSpanLifecycle()} — realistic end-to-end: startSpan → activateSpan → setTag × 3
 *       → scope.close() → span.finish()
 *   <li>{@link #finishWithDuration()} — finish with explicit duration (skips wall-clock
 *       calculation)
 * </ul>
 *
 * <p>NOTE: This is a multi-threaded benchmark; single-threaded runs don't accurately reflect
 * lock-contention on the trace ref counter.
 *
 * <p>Use -t 1 for a single-threaded run.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class SpanFinishBenchmark {

  // ListWriter is used to prevent actual I/O while still exercising the
  // full PendingTrace → Writer hand-off path.
  static final CoreTracer TRACER = CoreTracer.builder().writer(new ListWriter()).build();

  /**
   * Measures the cost of finishing a span: AtomicLong CAS to record durationNano, metrics
   * increment, and the PendingTrace.onPublish() ref-count decrement that may trigger a write.
   *
   * <p>A new span must be created per invocation because a span can only be finished once.
   */
  @Benchmark
  public void finishSpan() {
    AgentSpan span = TRACER.startSpan("benchmark", "finishSpan");
    span.finish();
  }

  /**
   * Measures a realistic end-to-end span lifecycle: start → activate → set tags → close scope →
   * finish. This exercises the scope-manager stack in addition to span finish.
   */
  @Benchmark
  public void fullSpanLifecycle() {
    AgentSpan span = TRACER.startSpan("benchmark", "fullSpanLifecycle");
    try (AgentScope scope = TRACER.activateSpan(span)) {
      span.setTag("http.method", "GET");
      span.setTag("http.status_code", 200);
      span.setTag("http.url", "https://example.com/api/resource");
    }
    span.finish();
  }

  /**
   * Measures finishing a span with an explicit pre-computed duration, bypassing wall-clock
   * subtraction. Isolates the cost of the CAS + ref-count decrement path.
   *
   * <p>A new span must be created per invocation because a span can only be finished once.
   */
  @Benchmark
  public void finishWithDuration() {
    AgentSpan span = TRACER.startSpan("benchmark", "finishWithDuration");
    span.finishWithDuration(1_000_000L); // 1 ms expressed in nanoseconds
  }
}

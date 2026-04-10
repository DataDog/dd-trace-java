package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark of ContinuableScopeManager activate/close operations.
 *
 * <p>These operations run on every instrumented method entry/exit and are critical to agent
 * overhead. Scopes are thread-local, so {@link Scope#Thread} state is used to give each benchmark
 * thread its own span without contention.
 *
 * <p>NOTE: This is a multi-threaded benchmark; single threaded benchmarks don't accurately reflect
 * some of the optimizations.
 *
 * <p>Use -t 1, if you'd like to do a single threaded run
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class ScopeLifecycleBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  /**
   * Per-thread state holding the spans used across benchmarks. Spans are created fresh each
   * iteration to avoid interference between measurement rounds.
   */
  @State(Scope.Thread)
  public static class ThreadState {
    AgentSpan span;
    AgentSpan childSpan;

    @Setup(Level.Iteration)
    public void setup() {
      span = TRACER.startSpan("benchmark", "parent");
      childSpan = TRACER.startSpan("benchmark", "child");
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
      childSpan.finish();
      span.finish();
    }
  }

  /**
   * Benchmarks the full activate-then-close lifecycle for a span. This is the common case: a method
   * entry activates a span and method exit closes the scope.
   */
  @Benchmark
  public void activateAndClose(ThreadState state) {
    AgentScope scope = TRACER.activateSpan(state.span);
    scope.close();
  }

  /**
   * Benchmarks re-activating the span that is already at the top of the scope stack. When the same
   * span is reactivated, {@link datadog.trace.core.scopemanager.ContinuableScopeManager} takes a
   * reference-counting fast path instead of creating a new scope.
   */
  @Benchmark
  public void activateSameSpan(ThreadState state) {
    AgentScope outer = TRACER.activateSpan(state.span);
    AgentScope inner = TRACER.activateSpan(state.span);
    inner.close();
    outer.close();
  }

  /**
   * Benchmarks activating a child span while a parent scope is already on the stack, then closing
   * both. This exercises the scope stack push/pop path with two distinct spans.
   */
  @Benchmark
  public void nestedActivateAndClose(ThreadState state) {
    AgentScope parentScope = TRACER.activateSpan(state.span);
    AgentScope childScope = TRACER.activateSpan(state.childSpan);
    childScope.close();
    parentScope.close();
  }

  /**
   * Benchmarks the {@code activeSpan()} lookup, which performs a ThreadLocal read to return the
   * span at the top of the current scope stack. This is called frequently by instrumentation that
   * needs the current span without starting a new scope.
   */
  @Benchmark
  public AgentSpan activeSpanLookup() {
    return TRACER.activeSpan();
  }
}

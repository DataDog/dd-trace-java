package datadog.context;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks attaching the same non-root context {@code depth} times then closing all scopes in
 * LIFO order, isolating the same-context fast-path cost from the general attach/close benchmarks in
 * {@link ContextManagerBenchmark}.
 *
 * <p>In {@link ThreadLocalContextManager} each re-attach after the first returns a no-op scope. In
 * {@link datadog.trace.core.scopemanager.ContinuableScopeManager} the first attach creates the
 * scope and subsequent re-attaches increment its reference count; each close decrements it, with
 * the final close doing the real work.
 *
 * <p>Run with:
 *
 * <pre>
 * {@code ./gradlew :dd-trace-core:jmh -Pjmh.includes=ContextManagerDepthBenchmark -PtestJvm=25 -Pjmh.profilers=gc}
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class ContextManagerDepthBenchmark {

  /**
   * Which {@link ContextManager} implementation to benchmark.
   *
   * @see ContextManagerBenchmark#managerType
   */
  @Param({"ThreadLocal", "Continuable"})
  public String managerType;

  @Param({"1", "4", "8", "100"})
  public int depth;

  ContextManager manager;
  Context[] contexts;

  @Setup
  public void setup() {
    manager = ContextManagerBenchmark.createManager(managerType);
    contexts = ContextManagerBenchmark.createContexts();
  }

  @State(Scope.Thread)
  public static class ThreadState {
    final ContextScope[] scopes = new ContextScope[100];

    int nextContextIndex;

    Context nextContext(Context[] contexts) {
      return contexts[(nextContextIndex++) & (ContextManagerBenchmark.CONTEXT_COUNT - 1)];
    }
  }

  // ── Benchmark ─────────────────────────────────────────────────────────────

  /** Attach the same context {@code depth} times then close all scopes in LIFO order. */
  @Benchmark
  public void attachSameContextDepth(ThreadState thread) {
    Context ctx = thread.nextContext(contexts);
    ContextScope[] scopes = thread.scopes;
    for (int i = 0; i < depth; i++) {
      scopes[i] = manager.attach(ctx);
    }
    for (int i = depth - 1; i >= 0; i--) {
      scopes[i].close();
    }
  }
}

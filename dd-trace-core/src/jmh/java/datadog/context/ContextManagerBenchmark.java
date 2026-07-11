package datadog.context;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.core.scopemanager.ContinuableScopeManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares {@link ThreadLocalContextManager} vs {@link ContinuableScopeManager} across context
 * attach, swap, and cross-thread continuation scenarios — including virtual threads (requires JDK
 * 21+).
 *
 * <p>For the same-non-root-context stack-depth scenario see {@link ContextManagerDepthBenchmark}.
 *
 * <p>Run with:
 *
 * <pre>
 * {@code ./gradlew :dd-trace-core:jmh -Pjmh.includes=ContextManagerBenchmark -PtestJvm=25 -Pjmh.profilers=gc}
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class ContextManagerBenchmark {

  // ── Constants ──────────────────────────────────────────────────────────────

  // Reflective access to Thread.ofVirtual().factory() (Java 21+).
  // Used to create fixed-size pools of virtual threads so no new VT is spawned per task.
  // Falls back to platform threads on older JVMs — the benchmark still runs, but
  // captureAndResumeOnVirtualThread and captureAndFanOutToVirtualThreads will measure
  // platform-thread overhead instead.
  static final boolean VIRTUAL_THREADS_AVAILABLE;
  static final ThreadFactory VIRTUAL_OR_PLATFORM_FACTORY;

  static {
    ThreadFactory factory = null;
    try {
      Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
      factory = (ThreadFactory) builder.getClass().getMethod("factory").invoke(builder);
    } catch (Exception ignored) {
    }
    VIRTUAL_THREADS_AVAILABLE = factory != null;
    VIRTUAL_OR_PLATFORM_FACTORY = factory != null ? factory : Thread::new;
  }

  // Creates a fixed pool whose threads are virtual (Java 21+) or platform (older JVMs).
  // Using a fixed pool rather than newVirtualThreadPerTaskExecutor avoids spawning a
  // fresh virtual thread on every task submission, keeping thread-creation cost out of
  // the measured critical path.
  static ExecutorService newFixedVirtualPool(int nThreads) {
    return Executors.newFixedThreadPool(nThreads, VIRTUAL_OR_PLATFORM_FACTORY);
  }

  static final ContextKey<String> KEY = ContextKey.named("benchmark-key");
  // power of 2 so cycling wraps cheaply with bit-mask
  static final int CONTEXT_COUNT = 16;
  // virtual threads spawned per continuation fan-out
  static final int FAN_OUT = 8;

  // ── Parameters ─────────────────────────────────────────────────────────────

  /**
   * Which {@link ContextManager} implementation to benchmark.
   *
   * <p>{@code ThreadLocal} — {@link ThreadLocalContextManager} (the lightweight default).
   *
   * <p>{@code Continuable} — {@link ContinuableScopeManager} (the full scope/span manager).
   */
  @Param({"ThreadLocal", "Continuable"})
  public String managerType;

  // ── Benchmark-scoped shared state ─────────────────────────────────────────

  ContextManager manager;
  // CONTEXT_COUNT distinct non-root contexts; threads cycle through them to
  // avoid artificial same-context hits in benchmarks that don't want them
  Context[] contexts;

  @Setup
  public void setup() {
    manager = createManager(managerType);
    contexts = createContexts();
  }

  static ContextManager createManager(String type) {
    if ("Continuable".equals(type)) {
      ContinuableScopeManager csm = new ContinuableScopeManager(0, false);
      return new ContextManager() {
        @Override
        public Context current() {
          return csm.current();
        }

        @Override
        public ContextScope attach(Context ctx) {
          return csm.attach(ctx);
        }

        @Override
        public Context swap(Context ctx) {
          return csm.swap(ctx);
        }

        @Override
        public ContextContinuation capture(Context ctx) {
          return csm.capture(ctx);
        }

        @Override
        public void addListener(ContextListener l) {}
      };
    }
    return ThreadLocalContextManager.INSTANCE;
  }

  static Context[] createContexts() {
    Context[] contexts = new Context[CONTEXT_COUNT];
    for (int i = 0; i < CONTEXT_COUNT; i++) {
      contexts[i] = Context.root().with(KEY, "value-" + i);
    }
    return contexts;
  }

  // ── Per-thread state ───────────────────────────────────────────────────────

  @State(Scope.Thread)
  public static class ThreadState {
    int index;
    // Pre-allocated barrier reused across fan-out invocations.
    // Avoids a new CountDownLatch allocation per invocation that would inflate gc.alloc.rate.norm.
    final Semaphore fanOutBarrier = new Semaphore(0);
    ExecutorService platformExecutor;
    ExecutorService virtualExecutor;

    @Setup(Level.Trial)
    public void setup() {
      // Both pools are fixed-size so no new thread is created per submitted task.
      // The virtual pool uses virtual threads (Java 21+) or falls back to platform threads.
      // Pool size is intentionally larger than the JMH thread count to avoid executor starvation
      // when benchmark threads all submit tasks concurrently.
      platformExecutor = Executors.newFixedThreadPool(16);
      virtualExecutor = newFixedVirtualPool(16);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
      platformExecutor.awaitTermination(10, SECONDS);
      virtualExecutor.awaitTermination(10, SECONDS);
    }

    Context nextContext(Context[] contexts) {
      return contexts[(index++) & (CONTEXT_COUNT - 1)];
    }
  }

  // ── Thread state with a pre-attached context (for read benchmarks) ─────────

  /**
   * Attaches a context once per trial so that {@link #current} and {@link #currentAndGet} measure
   * only the read path, not the attach overhead.
   */
  @State(Scope.Thread)
  public static class ActiveContextState {
    ContextScope scope;

    @Setup(Level.Trial)
    public void setup(ContextManagerBenchmark benchmark) {
      scope = benchmark.manager.attach(benchmark.contexts[0]);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      scope.close();
    }
  }

  // ── Scenario 1: attach a different context, close scope ───────────────────

  /** Attach one distinct context then close its scope. The hot path for most instrumentations. */
  @Benchmark
  public void attachAndClose(ThreadState thread) {
    Context ctx = thread.nextContext(contexts);
    try (ContextScope scope = manager.attach(ctx)) {
      // scope is active
    }
  }

  // ── Scenario 2: nested attach of two different contexts ───────────────────

  /**
   * Attach two distinct contexts in sequence and close both. Exercises the stack push/pop cycle
   * that occurs at every instrumented method boundary.
   */
  @Benchmark
  public void nestedAttachAndClose(ThreadState thread) {
    Context outer = thread.nextContext(contexts);
    Context inner = thread.nextContext(contexts);
    try (ContextScope outerScope = manager.attach(outer)) {
      try (ContextScope innerScope = manager.attach(inner)) {
        // inner is active
      }
    }
  }

  // ── Scenario 3: swap different contexts ───────────────────────────────────

  /**
   * Swap in a new context then swap back the previous one. {@link
   * ContinuableScopeManager#swap(Context)} replaces the entire scope stack, making this a heavier
   * operation than in {@link ThreadLocalContextManager}.
   *
   * <p>Note: GCProfiler will show allocation asymmetry here by design. {@link
   * ContinuableScopeManager} swap allocates a {@code ScopeStack}, a {@code ContinuableScope}, and a
   * {@code ScopeContext} per invocation; {@link ThreadLocalContextManager} swap is a plain field
   * write. That asymmetry is the real cost of each manager's swap operation, not scaffolding.
   */
  @Benchmark
  public void swapContexts(ThreadState thread) {
    Context ctx = thread.nextContext(contexts);
    Context previous = manager.swap(ctx);
    manager.swap(previous);
  }

  // ── Scenario 4: capture + same-thread resume (continuation baseline) ───────

  /**
   * Capture the current context as a continuation and immediately resume it on the same thread.
   * Establishes the allocation and atomic-counter cost of the continuation mechanism without any
   * cross-thread scheduling overhead.
   */
  @Benchmark
  public void captureThenResumeSameThread(ThreadState thread) {
    Context ctx = thread.nextContext(contexts);
    try (ContextScope scope = manager.attach(ctx)) {
      ContextContinuation cont = manager.capture(ctx);
      try (ContextScope resumed = cont.resume()) {
        // context restored on the same thread
      }
    }
  }

  // ── Scenario 5: capture, resume on a platform thread ─────────────────────

  /**
   * Capture the current context as a continuation and resume it on a pooled platform thread.
   * Measures cross-thread handoff latency (submit + schedule + execute) for each manager.
   *
   * <p>Fewer JMH threads than the default so the platform executor is never saturated.
   */
  @Benchmark
  @Threads(4)
  public void captureAndResumeOnPlatformThread(ThreadState thread) throws Exception {
    captureAndResumeOnExecutor(thread, thread.platformExecutor);
  }

  // ── Scenario 6: capture, resume on a virtual thread ──────────────────────

  /**
   * Capture the current context as a continuation and resume it on a fixed-pool virtual thread.
   * Shows how well each manager scales when continuations are used for structured concurrency or
   * reactive pipelines on virtual threads.
   */
  @Benchmark
  @Threads(4)
  public void captureAndResumeOnVirtualThread(ThreadState thread) throws Exception {
    captureAndResumeOnExecutor(thread, thread.virtualExecutor);
  }

  private void captureAndResumeOnExecutor(ThreadState thread, ExecutorService executor)
      throws Exception {
    Context ctx = thread.nextContext(contexts);
    try (ContextScope scope = manager.attach(ctx)) {
      ContextContinuation cont = manager.capture(ctx);
      CompletableFuture.runAsync(
              () -> {
                try (ContextScope resumed = cont.resume()) {
                  // context propagated to executor thread
                }
              },
              executor)
          .get(10, SECONDS);
    }
  }

  // ── Scenario 7: fan-out — one held continuation resumed on N virtual threads

  /**
   * Capture one context, hold the continuation, then fan it out to {@value #FAN_OUT} virtual
   * threads concurrently. Each virtual thread resumes the same continuation and closes its scope;
   * only the explicit {@link ContextContinuation#release()} after the barrier completes the
   * lifecycle.
   *
   * <p>This reflects async frameworks that dispatch a single request context to a pool of worker
   * coroutines / virtual threads.
   *
   * <p>Uses {@link Mode#SampleTime} to capture percentile tail latency in addition to the mean.
   * Warmup and measurement windows are extended because each invocation waits for {@value #FAN_OUT}
   * round-trips before returning.
   */
  @Benchmark
  @Threads(2)
  @BenchmarkMode(Mode.SampleTime)
  @Warmup(iterations = 3, time = 3)
  @Measurement(iterations = 5, time = 5)
  public void captureAndFanOutToVirtualThreads(ThreadState thread) throws Exception {
    Context ctx = thread.nextContext(contexts);
    try (ContextScope scope = manager.attach(ctx)) {
      ContextContinuation cont = manager.capture(ctx).hold();
      Semaphore barrier = thread.fanOutBarrier;
      for (int i = 0; i < FAN_OUT; i++) {
        thread.virtualExecutor.execute(
            () -> {
              try (ContextScope resumed = cont.resume()) {
                // each virtual thread sees the same captured context
              } finally {
                barrier.release();
              }
            });
      }
      try {
        if (!barrier.tryAcquire(FAN_OUT, 10, SECONDS)) {
          throw new IllegalStateException("fan-out timed out");
        }
      } finally {
        cont.release();
      }
    }
  }

  // ── Scenario 8: read the current context ─────────────────────────────────

  /**
   * Returns the currently active context. The most frequent operation in any traced application —
   * called at every instrumented method boundary before reading a span or key.
   */
  @Benchmark
  public Context current(ActiveContextState active) {
    return manager.current();
  }

  // ── Scenario 9: read a value from the current context ────────────────────

  /**
   * Returns a value from the currently active context. The full "read active span" path that
   * instrumentation executes at every traced method boundary.
   */
  @Benchmark
  public Object currentAndGet(ActiveContextState active) {
    return manager.current().get(KEY);
  }
}

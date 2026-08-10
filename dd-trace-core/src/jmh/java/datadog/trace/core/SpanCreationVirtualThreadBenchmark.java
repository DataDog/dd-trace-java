package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Runs span creation on a <b>virtual thread</b> — the regime the platform-thread {@link
 * SpanCreationBenchmark} is blind to.
 *
 * <p>Why this exists: {@code startSpan}'s thread-local {@code SpanBuilder} reuse (1.55, #9537) is
 * deliberately <b>disabled on virtual threads</b> (an {@code isVirtualThread} guard — thread-local
 * caching on numerous short-lived virtual threads is an anti-pattern), so on a virtual thread 1.55
 * still allocates a builder per {@code startSpan}. The full builder bypass (1.57, #9998) removes
 * that allocation for everyone, including virtual threads. On platform threads the reuse already
 * ate the allocation, so 1.57 shows nothing there; on virtual threads it should show as a per-span
 * allocation drop at 1.57. This bench is where that appears.
 *
 * <p><b>Requires a JDK with virtual threads (21+) at run time.</b> To keep the jmh source set
 * compilable on older toolchains, the virtual thread is started via reflection ({@code
 * Thread.startVirtualThread}); {@link #setup()} fails fast on a pre-21 JDK. The per-op vthread
 * spawn + join cost is constant across tracer versions, so it cancels in the 1.55→1.57 delta (read
 * the delta, not the absolute B/op).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(4)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class SpanCreationVirtualThreadBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String OPERATION_NAME = "servlet.request";

  CoreTracer tracer;
  // Thread.startVirtualThread(Runnable) -> Thread, resolved reflectively (JDK 21+).
  private MethodHandle startVirtualThread;
  // Reused so no per-op capturing-lambda allocation muddies the measurement.
  private Runnable spanTask;

  @Setup
  public void setup(Blackhole blackhole) throws Throwable {
    this.tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    this.startVirtualThread =
        MethodHandles.publicLookup()
            .findStatic(
                Thread.class,
                "startVirtualThread",
                MethodType.methodType(Thread.class, Runnable.class));
    this.spanTask =
        () -> {
          AgentSpan span = tracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME);
          span.finish();
        };
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  /** create + finish a bare span on a fresh virtual thread; join. */
  @Benchmark
  public void bareStartSpanOnVirtualThread() throws Throwable {
    Thread vthread = (Thread) startVirtualThread.invokeExact(spanTask);
    vthread.join();
  }
}

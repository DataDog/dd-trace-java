package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.context.ContextContinuation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
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
 * Fan-out traces: every benchmark thread finishes child spans into one shared trace, the shapes
 * where one {@link PendingTrace} takes concurrent finishes, spills past its inline slots, and is
 * drained while finishes continue. Where {@link TraceAssemblyBenchmark} keeps each trace on one
 * thread, these put all threads' finishes into the same trace.
 *
 * <ul>
 *   <li>{@link #childOfOpenRoot}: a batch job -- a long-lived root, open throughout, its children
 *       drained by partial flush every {@link #PARTIAL_FLUSH_MIN_SPANS} spans, on whichever
 *       finishing thread crosses the threshold.
 *   <li>{@link #childAfterRootFinished}: a short-lived root that captured its context for a
 *       background job, then finished and was written. Each child is a late arrival, so its finish
 *       also re-enqueues the trace with the pending-trace buffer. Under steady load partial flush
 *       still does the draining: the buffer's worker only force-writes a busy trace once its oldest
 *       finished span is 5s old.
 * </ul>
 *
 * <p>Run at 1 and N threads ({@code -t}); the 1-thread run is the uncontended baseline for the same
 * shapes. Spans finish against {@link DropWriter}, so serialization is out of the picture. Read
 * allocation ({@code -prof gc}) alongside time.
 */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@Threads(8)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class FanOutTraceBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String ROOT_OPERATION = "batch.job";
  private static final String CHILD_OPERATION = "postgresql.query";

  /** The tracer default, fixed here so the drain cadence doesn't depend on configuration. */
  static final int PARTIAL_FLUSH_MIN_SPANS = 1000;

  @State(Scope.Benchmark)
  public static class OpenRoot {
    CoreTracer tracer;
    AgentSpan root;

    @Setup
    public void setup(Blackhole blackhole) {
      tracer = newTracer(blackhole);
      root = tracer.buildSpan(INSTRUMENTATION_NAME, ROOT_OPERATION).start();
    }

    @TearDown
    public void tearDown() {
      root.finish();
      tracer.close();
    }
  }

  @State(Scope.Benchmark)
  public static class FinishedRoot {
    CoreTracer tracer;
    AgentSpan root;
    ContextContinuation continuation;

    @Setup
    public void setup(Blackhole blackhole) {
      tracer = newTracer(blackhole);
      root = tracer.buildSpan(INSTRUMENTATION_NAME, ROOT_OPERATION).start();
      continuation = tracer.capture(root);
      root.finish();
      // the root's write, as the pending-trace buffer would do once the trace went quiet, so every
      // child is a late arrival from the first iteration
      ((PendingTrace) ((DDSpan) root).spanContext().getTraceCollector()).write();
    }

    @TearDown
    public void tearDown() {
      continuation.release();
      tracer.close();
    }
  }

  /** One client-shaped child of a root that stays open: a batch job's fan-out. */
  @Benchmark
  public void childOfOpenRoot(OpenRoot state) {
    finishChild(state.tracer, state.root);
  }

  /** One client-shaped child of a finished root, via its captured context: a background job. */
  @Benchmark
  public void childAfterRootFinished(FinishedRoot state) {
    finishChild(state.tracer, state.root);
  }

  private static CoreTracer newTracer(Blackhole blackhole) {
    return CoreTracer.builder()
        .writer(new DropWriter(blackhole))
        .partialFlushMinSpans(PARTIAL_FLUSH_MIN_SPANS)
        .build();
  }

  private static void finishChild(CoreTracer tracer, AgentSpan root) {
    AgentSpan child =
        tracer.buildSpan(INSTRUMENTATION_NAME, CHILD_OPERATION).asChildOf(root).start();
    child.setTag(Tags.COMPONENT, "java-jdbc-prepared_statement");
    child.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    child.setTag(Tags.DB_TYPE, "postgresql");
    child.setTag(Tags.DB_INSTANCE, "jobs");
    child.finish();
  }
}

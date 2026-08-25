package datadog.smoketest.opentelemetry;

import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * This application traces a batch job that fans out to worker threads and fans back in, using both
 * the OpenTelemetry {@link Tracer} API and the {@link WithSpan} annotation.
 *
 * <p>It produces two traces:
 *
 * <ul>
 *   <li>a single annotated span from {@link #loadConfiguration()},
 *   <li>the batch trace:
 *       <pre>
 * batch-job                        (tracer, SERVER, root)
 * ├─ Application.splitBatch        (annotated, main thread)
 * ├─ shard-0                       (tracer, worker thread)   ┐ fan-out, parented by the
 * ├─ shard-1                       (tracer, worker thread)   ├ tracer's executor
 * ├─ shard-2                       (tracer, worker thread)   ┘ instrumentation
 * └─ batch-merge                   (tracer, links to the three shards) ← fan-in
 *    └─ Application.reportResults  (annotated, main thread)
 *       </pre>
 * </ul>
 */
public class Application {
  /** The minimum application run time. */
  private static final int MIN_RUNTIME_SECONDS = 5;

  /** The number of shards the batch fans out to, one span each. */
  private static final int SHARD_COUNT = 3;

  /** The work each shard simulates, so shard spans have a non-zero duration. */
  private static final long SHARD_WORK_MILLIS = 100;

  /** How long to wait for the fan-out pool to wind down before giving up. */
  private static final long POOL_SHUTDOWN_TIMEOUT_SECONDS = 30;

  /** OpenTelemetry tracer. */
  private static final Tracer TRACER =
      GlobalOpenTelemetry.getTracerProvider().tracerBuilder("smoke-app").build();

  public static void main(String[] args) throws Exception {
    long startTime = nanoTime();
    loadConfiguration();
    runBatch();
    stayAliveFor(MIN_RUNTIME_SECONDS, startTime);
  }

  /** Runs the batch job: split the work, fan it out over a thread pool, then merge the results. */
  private static void runBatch() throws Exception {
    Span batch = TRACER.spanBuilder("batch-job").setSpanKind(SERVER).startSpan();
    try (Scope scope = batch.makeCurrent()) {
      splitBatch();
      mergeShards(runShards());
    } finally {
      batch.end();
    }
  }

  /**
   * Fans the batch out over a thread pool, one span per shard, and waits for all of them.
   *
   * @return The context of every shard span, all of them ended, in shard order.
   */
  private static List<SpanContext> runShards() throws Exception {
    // The app runs on the Java 8 test matrix, where ExecutorService is not AutoCloseable yet.
    ExecutorService pool = Executors.newFixedThreadPool(SHARD_COUNT);
    try {
      List<Future<SpanContext>> pendingShards = new ArrayList<>(SHARD_COUNT);
      for (int shard = 0; shard < SHARD_COUNT; shard++) {
        pendingShards.add(pool.submit(shardTask(shard)));
      }
      List<SpanContext> shardContexts = new ArrayList<>(SHARD_COUNT);
      for (Future<SpanContext> pendingShard : pendingShards) {
        // Every shard span is started and ended within its task, so they are all complete here.
        shardContexts.add(pendingShard.get());
      }
      return shardContexts;
    } finally {
      pool.shutdown();
      pool.awaitTermination(POOL_SHUTDOWN_TIMEOUT_SECONDS, SECONDS);
    }
  }

  /**
   * Builds the work of a single shard. The task carries no context of its own: the shard span must
   * come out as a child of the batch from the tracer's executor instrumentation alone, the way it
   * would for an application that just submits work to a pool.
   *
   * @param shard The shard index.
   * @return The shard work, returning the context of its (ended) span.
   */
  private static Callable<SpanContext> shardTask(int shard) {
    return () -> {
      Span span = TRACER.spanBuilder("shard-" + shard).startSpan();
      try (Scope shardScope = span.makeCurrent()) {
        span.setAttribute("batch.shard", shard);
        MILLISECONDS.sleep(SHARD_WORK_MILLIS);
        return span.getSpanContext();
      } finally {
        span.end();
      }
    };
  }

  /**
   * Fans the shards back in: a single span linking to every shard span it merges.
   *
   * @param shardContexts The context of every shard span to link to.
   */
  private static void mergeShards(List<SpanContext> shardContexts) {
    SpanBuilder builder = TRACER.spanBuilder("batch-merge");
    for (SpanContext shardContext : shardContexts) {
      builder.addLink(shardContext);
    }
    Span merge = builder.startSpan();
    try (Scope scope = merge.makeCurrent()) {
      reportResults(shardContexts.size());
    } finally {
      merge.end();
    }
  }

  @WithSpan
  static void loadConfiguration() {
    Span.current().addEvent("configuration-loaded");
  }

  @WithSpan
  static void splitBatch() {
    Span.current().setAttribute("batch.shards", SHARD_COUNT);
  }

  @WithSpan
  static void reportResults(int mergedShards) {
    Span.current().setAttribute("batch.merged-shards", mergedShards);
  }

  /**
   * Keeps the application alive until it has run for at least {@code minRuntimeSeconds}. The tracer
   * boots its telemetry asynchronously on daemon threads, so a batch application that exits within
   * a few hundred milliseconds can be gone before {@code app-started} is ever sent — which the
   * smoke test asserts.
   *
   * @param minRuntimeSeconds The floor on the total application runtime, in seconds.
   * @param startTime The {@link System#nanoTime()} the application started at.
   */
  private static void stayAliveFor(long minRuntimeSeconds, long startTime)
      throws InterruptedException {
    long remainingNanos = SECONDS.toNanos(minRuntimeSeconds) - (nanoTime() - startTime);
    if (remainingNanos > 0) {
      NANOSECONDS.sleep(remainingNanos);
    }
  }
}

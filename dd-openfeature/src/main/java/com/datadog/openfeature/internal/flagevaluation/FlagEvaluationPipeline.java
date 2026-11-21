package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.EventPipeline.awaitThread;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.datadog.openfeature.internal.EventPipeline;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EVP flagevaluation writer for Java.
 *
 * <p>Evaluations are aggregated on the evaluating threads, into a {@link ConcurrentFlagAggregator}
 * (see it for the tiers, the caps and the bucket keys), so no evaluation waits on a queue or is
 * dropped for lack of room in one. The worker thread only flushes: every flush interval it drains
 * the buckets touched since the last flush into payloads, sweeps out the idle ones, and posts the
 * payloads through the same event transport as the exposure pipeline.
 *
 * <p>Shutdown: {@link #requestShutdown} stops accepting evaluations, counting later ones as closed
 * drops, and wakes the worker for a final flush. Because recording is lock-free, an evaluation can
 * still land after that flush; once the worker has exited, {@link #awaitTermination} drains the
 * remainder and counts it as closed drops, so shutdown loss is observable rather than silent.
 */
public class FlagEvaluationPipeline implements FlagEvaluationWriter, EventPipeline {

  private static final Logger LOGGER = LoggerFactory.getLogger(FlagEvaluationPipeline.class);

  public static final int FLUSH_INTERVAL_SECONDS = 10;

  static final int FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES = 5 * 1024 * 1024;
  static final String FLAG_EVALUATION_DROPPED_METRIC = "flagevaluation.rows.dropped";
  static final String FLAG_EVALUATION_DEGRADED_METRIC = "flagevaluation.rows.degraded";
  static final String FLAG_EVALUATION_SPLITS_METRIC = "flagevaluation.payload.splits";
  static final String FLAG_EVALUATION_CONTEXT_TRUNCATED_METRIC = "flagevaluation.context.truncated";
  static final String DROP_REASON_CLOSED = "closed";
  static final String DROP_REASON_DEGRADED_CAP = "degraded_cap";
  static final String DROP_REASON_PAYLOAD_LIMIT = "payload_limit";
  static final String DEGRADED_REASON_CARDINALITY_CAP = "cardinality_cap";
  static final String DEGRADED_REASON_PAYLOAD_LIMIT = "payload_limit";
  private static final String FLAG_EVALUATION_ROUTE = "flagevaluation";

  private final RuntimeServices services;
  private final ConcurrentFlagAggregator aggregator = new ConcurrentFlagAggregator();
  private final FlagEvaluationSerializingHandler serializer;
  private final Thread serializerThread;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Per-reason-tag counters for evaluations whose context was truncated by copyPrunedContext. Keyed
   * by the sorted comma-separated reason string (e.g. "max_key_length,max_value_length").
   * Incremented on the evaluating thread, drained and emitted on flush.
   */
  private final ConcurrentHashMap<String, AtomicLong> contextTruncatedCounts =
      new ConcurrentHashMap<>();

  public FlagEvaluationPipeline(
      final long flushInterval,
      final TimeUnit timeUnit,
      final EventTransport transport,
      final Map<String, String> context,
      final RuntimeServices services) {
    this.services = services;
    this.serializer =
        new FlagEvaluationSerializingHandler(
            transport,
            aggregator,
            flushInterval,
            timeUnit,
            context,
            contextTruncatedCounts,
            FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES,
            services);
    this.serializerThread = services.newThread("evaluations", serializer);
  }

  /** Starts the background serializing thread. */
  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get()) {
        return;
      }
      this.serializerThread.start();
    }
  }

  @Override
  public void requestShutdown() {
    synchronized (lifecycleLock) {
      if (closed.compareAndSet(false, true)) {
        // Wakes the worker for its final flush; never interrupts a current or final HTTP request.
        serializer.requestShutdown();
        // Logged last: a diagnostic must not keep the worker from being signaled.
        LOGGER.debug("Feature Flags flag evaluation shutdown requested");
      }
    }
  }

  @Override
  public void awaitTermination(final long deadlineNanos) {
    if (Thread.currentThread() == this.serializerThread) {
      return;
    }
    awaitThread(this.serializerThread, deadlineNanos);
    // Recording is lock-free, so an evaluation that passed the closed check before close() ran can
    // still land after the worker's final drain. Count the remainder rather than leave it silently
    // stranded. Only once the worker is gone, since the aggregator has one draining thread at a
    // time; if the wait timed out the worker is still flushing, so skip it.
    synchronized (lifecycleLock) {
      if (!this.serializerThread.isAlive()) {
        countResidualEvaluations();
      }
    }
  }

  /** Counts the evaluations recorded since the worker's last drain as closed drops. */
  private void countResidualEvaluations() {
    final long[] residual = {0};
    aggregator.drain((bucket, count, first, last, defaultUsed) -> residual[0] += count);
    countClosedDrops(residual[0]);
  }

  @Override
  public void record(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      final Map<String, Object> attrs,
      final Map<String, Object> features) {
    if (closed.get()) {
      countClosedDrops(1);
      return;
    }
    try {
      aggregator.record(
          flagKey,
          variant,
          allocationKey,
          targetingKey,
          errorMessage,
          evalTimeMs,
          observeFullEvaluationData,
          attrs,
          features);
    } catch (RuntimeException e) {
      // Never let telemetry break the evaluation, e.g. on a context value whose hashCode throws.
      LOGGER.debug("Could not record flag evaluation", e);
    }
  }

  @Override
  public void countContextTruncated(final String reason) {
    contextTruncatedCounts.computeIfAbsent(reason, k -> new AtomicLong(0)).incrementAndGet();
  }

  private void countClosedDrops(final long count) {
    services.countMetric(FLAG_EVALUATION_DROPPED_METRIC, count, DROP_REASON_CLOSED);
  }

  boolean isSerializerThreadAlive() {
    return serializerThread.isAlive();
  }

  /** Test seam: flushes without starting the worker. */
  void flushForTest() {
    serializer.flush();
  }

  // ---- Serializing handler (background thread logic) ----

  static class FlagEvaluationSerializingHandler implements Runnable {
    private final RuntimeServices services;
    private final EventTransport transport;
    private final ConcurrentFlagAggregator aggregator;
    private final long flushIntervalNanos;
    final Map<String, String> context;
    private final ConcurrentHashMap<String, AtomicLong> contextTruncatedCounts;
    private final int payloadSizeLimitBytes;

    // Counted down by requestShutdown(): wakes the worker for its final flush.
    private final CountDownLatch shutdownRequested = new CountDownLatch(1);

    FlagEvaluationSerializingHandler(
        final EventTransport transport,
        final ConcurrentFlagAggregator aggregator,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final ConcurrentHashMap<String, AtomicLong> contextTruncatedCounts,
        final int payloadSizeLimitBytes,
        final RuntimeServices services) {
      this.services = services;
      this.transport = transport;
      this.aggregator = aggregator;
      this.flushIntervalNanos = timeUnit.toNanos(flushInterval);
      this.context = context;
      this.contextTruncatedCounts = contextTruncatedCounts;
      this.payloadSizeLimitBytes = payloadSizeLimitBytes;
      LOGGER.debug("starting flag evaluation serializer");
    }

    private void countMetric(final String name, final long value, final String reason) {
      services.countMetric(name, value, reason);
    }

    /** Signals the worker to perform a final flush and exit. */
    void requestShutdown() {
      shutdownRequested.countDown();
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        // On exit (interrupt or shutdown request), flush what is aggregated so it is not lost.
        //
        // Normal close only signals shutdown. If an unrelated interrupt ends the duty cycle, clear
        // it during the final socket I/O and restore it afterwards: the flush otherwise fails
        // fast, and the drained rows are lost, invisibly to the drop counters.
        final boolean wasInterrupted = Thread.interrupted();
        try {
          flush();
        } finally {
          if (wasInterrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
      LOGGER.debug("flag evaluation processor worker exited.");
    }

    /** Flushes every flush interval until shutdown is requested. */
    private void runDutyCycle() throws InterruptedException {
      long lastFlush = System.nanoTime();
      while (true) {
        final long remaining = flushIntervalNanos - (System.nanoTime() - lastFlush);
        if (remaining <= 0) {
          flush();
          lastFlush = System.nanoTime();
        } else if (shutdownRequested.await(remaining, NANOSECONDS)) {
          return;
        }
      }
    }

    // ---- Flush logic ----

    void flush() {
      final long dgDrops = aggregator.droppedDegradedOverflow.getAndSet(0);
      countMetric(FLAG_EVALUATION_DROPPED_METRIC, dgDrops, DROP_REASON_DEGRADED_CAP);
      if (dgDrops > 0) {
        LOGGER.warn(
            "degraded aggregation tier full - dropped {} evaluation(s); raise degraded cap"
                + " (best-effort telemetry)",
            dgDrops);
      }

      // Drain per-reason context-truncation counters and emit one metric per unique reason tag.
      for (final Map.Entry<String, AtomicLong> entry : contextTruncatedCounts.entrySet()) {
        final long count = entry.getValue().getAndSet(0);
        if (count > 0) {
          countMetric(FLAG_EVALUATION_CONTEXT_TRUNCATED_METRIC, count, entry.getKey());
        }
      }

      try {
        final long flushTimeMs = System.currentTimeMillis();
        final List<FlagEvaluationPayloads.FlagEvaluationEvent> events = new ArrayList<>();
        final long[] degradedCount = {0};
        // The drain resets the counters it reads: a failed delivery drops this batch rather than
        // retrying it, so one unserializable value cannot block every later flush.
        aggregator.drain((bucket, count, first, last, defaultUsed) -> {
          if (bucket.degraded) {
            degradedCount[0] += count;
          }
          events.add(
              FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(
                  bucket, count, first, last, defaultUsed, flushTimeMs));
        });
        aggregator.sweepIdle();
        if (events.isEmpty()) {
          return;
        }
        countMetric(
            FLAG_EVALUATION_DEGRADED_METRIC, degradedCount[0], DEGRADED_REASON_CARDINALITY_CAP);
        // Each payload is posted as it closes, so a flush holds one payload at a time.
        final FlagEvaluationPayloads.PayloadCounts counts =
            new FlagEvaluationPayloads.PayloadCounts();
        try {
          FlagEvaluationPayloads.writePayloads(
              events,
              context,
              payloadSizeLimitBytes,
              counts,
              payload -> transport.post(FLAG_EVALUATION_ROUTE, payload));
        } finally {
          reportPayloadCounts(counts);
        }
        if (counts.payloads > 0) {
          LOGGER.debug(
              "Feature Flags flag evaluation flush delivered: payloads={}", counts.payloads);
        }
      } catch (IOException e) {
        // Delivery failures (agent unreachable, timeouts, error responses) are expected
        // environmental conditions for best-effort telemetry, not tracer bugs.
        LOGGER.debug(
            "Feature Flags flag evaluation flush failed: {}; dropping the batch", e.toString(), e);
      } catch (Exception e) {
        LOGGER.error("Could not submit flag evaluations", e);
      }
    }

    private void reportPayloadCounts(final FlagEvaluationPayloads.PayloadCounts counts) {
      countMetric(
          FLAG_EVALUATION_DROPPED_METRIC, counts.droppedPayloadLimit, DROP_REASON_PAYLOAD_LIMIT);
      countMetric(
          FLAG_EVALUATION_DEGRADED_METRIC,
          counts.degradedPayloadLimit,
          DEGRADED_REASON_PAYLOAD_LIMIT);
      if (counts.payloads > 1) {
        countMetric(FLAG_EVALUATION_SPLITS_METRIC, counts.payloads - 1, null);
      }
      if (counts.droppedPayloadLimit > 0) {
        LOGGER.warn(
            "flag evaluation payload too large - dropped {} evaluation(s)"
                + " (best-effort telemetry)",
            counts.droppedPayloadLimit);
      }
    }
  }
}

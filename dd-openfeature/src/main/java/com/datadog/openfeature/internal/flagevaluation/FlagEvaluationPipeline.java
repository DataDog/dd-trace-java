package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.EventPipeline.awaitThread;

import com.datadog.openfeature.internal.EventPipeline;
import com.datadog.openfeature.internal.EventQueue;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * <p>Uses the same event transport as the exposure pipeline, with two-tier aggregation replacing
 * the single-exposure buffer.
 *
 * <p>Two-tier aggregation contract: Full key: (flagKey, variant, allocationKey, runtimeDefault,
 * errorMessage, targetingKey, canonical-context-key). Degraded key: (flagKey, variant,
 * allocationKey, runtimeDefault, errorMessage) - no targetingKey/context. Canonical context key:
 * sorted entries, type-tagged length-delimited encoding - NOT a hash (collision-safe, comparable
 * string identity). Context pruning: deterministic (sort before cut), <=256 fields, string values
 * <=256 chars; the pruned attributes are what gets aggregated and serialized. Caps:
 * globalCap=131072, perFlagCap=10000, degradedCap=32768. Eval-time: min/max of
 * firstEvalMs/lastEvalMs across events in the same bucket. Runtime default: absent variant means
 * runtimeDefaultUsed=true. Flush interval: 10 seconds. Queue: bounded lock-free queue (capacity
 * 2^12), non-blocking offer; on overflow the event is dropped and the droppedQueueOverflow counter
 * is incremented and surfaced on flush. Enqueue: lock-free. Producers contend only on the MPSC
 * queue, never on a monitor, so evaluation threads do not serialize against each other. Shutdown:
 * close() drains the queue and performs a final flush before the worker thread exits. Because
 * enqueue is lock-free, a producer can still offer during shutdown; close() sweeps the queue once
 * the worker has been joined, counting any remainder as a closed drop so shutdown loss is
 * observable rather than silent.
 */
public class FlagEvaluationPipeline implements FlagEvaluationWriter, EventPipeline {

  private static final Logger LOGGER = LoggerFactory.getLogger(FlagEvaluationPipeline.class);

  public static final int DEFAULT_CAPACITY = 1 << 12; // 4096 elements, per cross-SDK RFC
  public static final int FLUSH_INTERVAL_SECONDS = 10;

  static final int FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES = 5 * 1024 * 1024;
  static final String FLAG_EVALUATION_DROPPED_METRIC = "flagevaluation.rows.dropped";
  static final String FLAG_EVALUATION_DEGRADED_METRIC = "flagevaluation.rows.degraded";
  static final String FLAG_EVALUATION_SPLITS_METRIC = "flagevaluation.payload.splits";
  static final String FLAG_EVALUATION_CONTEXT_TRUNCATED_METRIC = "flagevaluation.context.truncated";
  static final String DROP_REASON_QUEUE_OVERFLOW = "queue_overflow";
  static final String DROP_REASON_CLOSED = "closed";
  static final String DROP_REASON_DEGRADED_CAP = "degraded_cap";
  static final String DROP_REASON_PAYLOAD_LIMIT = "payload_limit";
  static final String DEGRADED_REASON_CARDINALITY_CAP = "cardinality_cap";
  static final String DEGRADED_REASON_PAYLOAD_LIMIT = "payload_limit";
  private static final String FLAG_EVALUATION_ROUTE = "flagevaluation";

  /**
   * PROTOTYPE: aggregate on the evaluating threads ({@link ConcurrentFlagAggregator}) instead of
   * queueing an event per evaluation for the worker. Off by default, so one build benchmarks both.
   */
  static final boolean CALLER_SIDE_AGGREGATION = Boolean.getBoolean("dd.ffe.callerSideAggregation");

  private final RuntimeServices services;

  private final EventQueue<FlagEvalEvent> queue;
  private final FlagEvaluationSerializingHandler serializer;
  private final Thread serializerThread;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private void countMetric(final String metricName, final long value, final String reason) {
    services.countMetric(metricName, value, reason);
  }

  /**
   * Observable counter for events dropped because the bounded hand-off queue was full when the hook
   * tried to enqueue (backpressure). Incremented on the hook thread, surfaced on flush.
   */
  private final AtomicLong droppedQueueOverflow = new AtomicLong(0);

  /**
   * Per-reason-tag counters for evaluations whose context was truncated by copyPrunedContext. Keyed
   * by the sorted comma-separated reason string (e.g. "max_key_length,max_value_length").
   * Incremented on the hook thread, drained and emitted on flush.
   */
  private final ConcurrentHashMap<String, AtomicLong> contextTruncatedCounts =
      new ConcurrentHashMap<>();

  public FlagEvaluationPipeline(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final EventTransport transport,
      final Map<String, String> context,
      final RuntimeServices services) {
    this(capacity, flushInterval, timeUnit, transport, context, services, CALLER_SIDE_AGGREGATION);
  }

  FlagEvaluationPipeline(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final EventTransport transport,
      final Map<String, String> context,
      final RuntimeServices services,
      final boolean callerSideAggregation) {
    this.services = services;
    this.queue = new EventQueue<>(capacity);
    this.serializer =
        new FlagEvaluationSerializingHandler(
            transport,
            queue,
            flushInterval,
            timeUnit,
            context,
            droppedQueueOverflow,
            contextTruncatedCounts,
            FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES,
            services,
            callerSideAggregation);
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

  /** Test seam: current full-tier bucket count in the worker's aggregator. */
  int aggregatorFullTierSizeForTest() {
    return serializer.aggregator.fullTierSize();
  }

  @Override
  public void requestShutdown() {
    synchronized (lifecycleLock) {
      if (closed.compareAndSet(false, true)) {
        // The worker polls every 100 ms: do not interrupt a current or final HTTP request.
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
    // enqueue() is lock-free, so a producer that passed the closed check before close() ran can
    // still land an event after the worker's final drain. Sweep the remainder so that loss is
    // counted rather than silently stranded in a queue nobody polls again. Only safe once the
    // worker is gone: the queue is single-consumer, so sweeping alongside a live worker would
    // break that contract. If the wait timed out the worker is still draining, so skip the sweep.
    synchronized (lifecycleLock) {
      if (!this.serializerThread.isAlive()) {
        sweepAndCountResidualEvents();
      }
    }
  }

  /**
   * Counts events left in the queue after the worker has exited. Must only be called when the
   * serializer thread is not alive - the queue permits a single consumer.
   */
  private void sweepAndCountResidualEvents() {
    long residual = 0;
    while (queue.poll() != null) {
      residual++;
    }
    countMetric(FLAG_EVALUATION_DROPPED_METRIC, residual, DROP_REASON_CLOSED);
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
    final ConcurrentFlagAggregator concurrent = serializer.concurrent;
    if (concurrent == null) {
      FlagEvaluationWriter.super.record(
          flagKey,
          variant,
          allocationKey,
          targetingKey,
          errorMessage,
          evalTimeMs,
          observeFullEvaluationData,
          attrs,
          features);
      return;
    }
    if (closed.get()) {
      countClosedDrop();
      return;
    }
    concurrent.record(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        errorMessage,
        evalTimeMs,
        observeFullEvaluationData,
        attrs,
        features);
  }

  @Override
  public void enqueue(final FlagEvalEvent event) {
    if (event == null) {
      return;
    }
    if (closed.get()) {
      countClosedDrop();
      return;
    }
    // Deliberately lock-free: the hand-off queue is MPSC by design, so serializing producers on a
    // monitor here would negate that and turn every evaluation in every application thread into
    // contention on one lock. A producer that passed the check above can still offer after close()
    // has started; that residue is accounted for by the worker's bounded post-drain passes and by
    // close()'s post-join sweep, so shutdown loss stays observable.
    //
    // Safe publication of the event (including the context snapshot built by the hook) comes from
    // the queue's own offer/poll ordering, not from any monitor held here.
    //
    // Non-blocking offer. Count overflow so loss is observable rather than silent; the count is
    // surfaced on the next flush. The hook's pre-queue guard (see FlagEvalLoggingHook) samples the
    // queue depth before doing any context-copy work, so a saturated queue costs an
    // AtomicInteger.get(); the offer here still races with the worker and can legitimately fail.
    if (!queue.offer(event)) {
      droppedQueueOverflow.incrementAndGet();
    }
  }

  @Override
  public boolean hasCapacityForEnqueue() {
    // Aggregating on the evaluating thread has no queue to fill.
    return serializer.concurrent != null || queue.size() < queue.capacity();
  }

  @Override
  public void countPreQueueOverflow() {
    droppedQueueOverflow.incrementAndGet();
  }

  @Override
  public void countContextTruncated(final String reason) {
    contextTruncatedCounts.computeIfAbsent(reason, k -> new AtomicLong(0)).incrementAndGet();
  }

  private void countClosedDrop() {
    countMetric(FLAG_EVALUATION_DROPPED_METRIC, 1, DROP_REASON_CLOSED);
  }

  /** Returns the count of events dropped due to queue-overflow backpressure (observable). */
  long droppedQueueOverflow() {
    return droppedQueueOverflow.get();
  }

  /** Test seam: returns one queued event without starting the worker. */
  FlagEvalEvent pollQueuedEventForTest() {
    return queue.poll();
  }

  boolean isSerializerThreadAlive() {
    return serializerThread.isAlive();
  }

  /** Test seam: flushes serializer state without starting the worker. */
  void flushForTest() {
    serializer.flush();
  }

  // ---- Serializing handler (background thread logic) ----

  static class FlagEvaluationSerializingHandler implements Runnable {
    private final RuntimeServices services;

    private void countMetric(String name, long value, String reason) {
      services.countMetric(name, value, reason);
    }

    private final EventQueue<FlagEvalEvent> queue;
    private final long ticksRequiredToFlush;

    @SuppressFBWarnings(
        value = "AT_NONATOMIC_64BIT_PRIMITIVE",
        justification = "the field is confined to the single serializer thread")
    private long lastTicks;

    private final EventTransport transport;
    final Map<String, String> context;
    private final AtomicLong droppedQueueOverflow;
    private final ConcurrentHashMap<String, AtomicLong> contextTruncatedCounts;
    private final int payloadSizeLimitBytes;
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    // PROTOTYPE: set when aggregating on the evaluating threads; the queue then stays empty.
    final ConcurrentFlagAggregator concurrent;

    // Shutdown coordination: set by close(), drives a final drain+flush before the worker exits.
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final CountDownLatch finalFlushDone = new CountDownLatch(1);

    FlagEvaluationSerializingHandler(
        final EventTransport transport,
        final EventQueue<FlagEvalEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final AtomicLong droppedQueueOverflow,
        final ConcurrentHashMap<String, AtomicLong> contextTruncatedCounts,
        final int payloadSizeLimitBytes,
        final RuntimeServices services,
        final boolean callerSideAggregation) {
      this.services = services;
      this.queue = queue;
      this.concurrent = callerSideAggregation ? new ConcurrentFlagAggregator() : null;
      this.transport = transport;
      this.context = context;
      this.droppedQueueOverflow = droppedQueueOverflow;
      this.contextTruncatedCounts = contextTruncatedCounts;
      this.payloadSizeLimitBytes = payloadSizeLimitBytes;
      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);
      LOGGER.debug("starting flag evaluation serializer");
    }

    /** Signals the worker to drain the queue and perform a final flush before exiting. */
    void requestShutdown() {
      shutdownRequested.set(true);
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        // On exit (interrupt or shutdown request), drain everything still buffered and flush it so
        // queued events are not lost on shutdown.
        //
        // Normal close only signals shutdown. If an unrelated interrupt ends the duty cycle, clear
        // it during the final socket I/O and restore it afterwards: the flush otherwise fails
        // fast, the aggregated rows are discarded by the clear() in flush(), and the loss is
        // invisible to the drop counters because those rows already left the queue.
        final boolean wasInterrupted = Thread.interrupted();
        try {
          drainAndFlush();
        } finally {
          finalFlushDone.countDown();
          if (wasInterrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
      LOGGER.debug("flag evaluation processor worker exited.");
    }

    private void runDutyCycle() throws InterruptedException {
      final Thread thread = Thread.currentThread();
      while (!thread.isInterrupted() && !shutdownRequested.get()) {
        final FlagEvalEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
        if (event != null) {
          aggregateEvent(event);
        }
        flushIfNecessary();
      }
    }

    void drainAndFlush() {
      FlagEvalEvent event;
      while ((event = queue.poll()) != null) {
        aggregateEvent(event);
      }
      flush();
    }

    // ---- Aggregation logic ----

    /** Routes an event into the full tier or degraded tier, or drops and counts on overflow. */
    void aggregateEvent(final FlagEvalEvent event) {
      try {
        aggregator.aggregate(event);
      } catch (LinkageError | RuntimeException e) {
        LOGGER.debug("Could not aggregate flag evaluation event", e);
      }
    }

    // ---- Flush logic ----

    void flushIfNecessary() {
      if (shouldFlush()) {
        flush();
      }
    }

    void flush() {
      // Surface backpressure (queue-overflow) drops as an observable warning even when there is
      // nothing else to flush.
      final long qDrops = droppedQueueOverflow.getAndSet(0);
      countMetric(FLAG_EVALUATION_DROPPED_METRIC, qDrops, DROP_REASON_QUEUE_OVERFLOW);
      if (qDrops > 0) {
        LOGGER.warn(
            "flag evaluation queue full - dropped {} evaluation(s) under backpressure"
                + " (best-effort telemetry)",
            qDrops);
      }
      final long dgDrops =
          concurrent != null
              ? concurrent.droppedDegradedOverflow.getAndSet(0)
              : aggregator.droppedDegradedOverflow.getAndSet(0);
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

      if (concurrent != null) {
        flushConcurrent();
        return;
      }
      if (aggregator.isEmpty()) {
        return;
      }
      try {
        countMetric(
            FLAG_EVALUATION_DEGRADED_METRIC,
            aggregator.degradedEvaluationCount(),
            DEGRADED_REASON_CARDINALITY_CAP);
        final List<FlagEvaluationPayloads.FlagEvaluationEvent> events = buildEventList();
        if (events.isEmpty()) {
          return;
        }
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
      } finally {
        // Best-effort: always clear the aggregator after a flush attempt. Retaining buckets across
        // flushes on encode failure would let one unserializable value (for example a NaN Double a
        // customer put in the context) permanently block every subsequent flush.
        aggregator.clear();
        lastTicks = System.nanoTime();
      }
    }

    /**
     * PROTOTYPE: drains the concurrent aggregator's touched buckets in place, serializes them, then
     * sweeps out the buckets idle for a whole window.
     */
    private void flushConcurrent() {
      try {
        final long flushTimeMs = System.currentTimeMillis();
        final List<FlagEvaluationPayloads.FlagEvaluationEvent> events = new ArrayList<>();
        final long[] degradedCount = {0};
        concurrent.drain((bucket, count, first, last, defaultUsed) -> {
          if (bucket.degraded) {
            degradedCount[0] += count;
          }
          events.add(
              FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(
                  bucket, count, first, last, defaultUsed, flushTimeMs));
        });
        concurrent.sweepIdle();
        if (events.isEmpty()) {
          return;
        }
        countMetric(
            FLAG_EVALUATION_DEGRADED_METRIC, degradedCount[0], DEGRADED_REASON_CARDINALITY_CAP);
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
      } catch (IOException e) {
        LOGGER.debug(
            "Feature Flags flag evaluation flush failed: {}; dropping the batch", e.toString(), e);
      } catch (Exception e) {
        LOGGER.error("Could not submit flag evaluations", e);
      } finally {
        lastTicks = System.nanoTime();
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

    private List<FlagEvaluationPayloads.FlagEvaluationEvent> buildEventList() {
      final long flushTimeMs = System.currentTimeMillis();
      // Consent is read per bucket from the value each event snapshotted at evaluation time, not
      // from the gateway here: CURRENT_CONFIG may have been overwritten by a later RC update since
      // these evaluations happened, and reading it at flush would apply the wrong config's consent.
      final List<FlagEvaluationPayloads.FlagEvaluationEvent> events =
          new ArrayList<>(aggregator.bucketCount());
      for (final FlagEvaluationAggregator.EvalBucket bucket : aggregator.fullBuckets()) {
        events.add(
            FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(
                bucket, true, bucket.observeFullEvaluationData, flushTimeMs));
      }
      for (final FlagEvaluationAggregator.EvalBucket bucket : aggregator.degradedBuckets()) {
        events.add(
            FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(
                bucket, false, bucket.observeFullEvaluationData, flushTimeMs));
      }
      return events;
    }

    private boolean shouldFlush() {
      final boolean empty = concurrent != null ? concurrent.isEmpty() : aggregator.isEmpty();
      if (empty && droppedQueueOverflow.get() == 0) {
        return false;
      }
      final long nanoTime = System.nanoTime();
      final long ticks = nanoTime - lastTicks;
      if (ticks > ticksRequiredToFlush) {
        lastTicks = nanoTime;
        return true;
      }
      return false;
    }
  }

  // ---- Test-seam inner class (package-private) ----

  /**
   * Test-accessible handler that exposes {@link #drainAndAggregate()} and {@link #flush()} without
   * starting a real background thread.
   */
  static class SerializingHandlerForTest extends FlagEvaluationSerializingHandler {

    SerializingHandlerForTest(
        final EventTransport transport,
        final Map<String, String> context,
        final RuntimeServices services) {
      this(transport, context, FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES, services);
    }

    SerializingHandlerForTest(
        final EventTransport transport,
        final Map<String, String> context,
        final int payloadSizeLimitBytes,
        final RuntimeServices services) {
      this(transport, context, payloadSizeLimitBytes, services, false);
    }

    SerializingHandlerForTest(
        final EventTransport transport,
        final Map<String, String> context,
        final int payloadSizeLimitBytes,
        final RuntimeServices services,
        final boolean callerSideAggregation) {
      super(
          transport,
          new EventQueue<>(DEFAULT_CAPACITY),
          Long.MAX_VALUE, // effectively never auto-flush
          TimeUnit.NANOSECONDS,
          context,
          new AtomicLong(0),
          new ConcurrentHashMap<>(),
          payloadSizeLimitBytes,
          services,
          callerSideAggregation);
    }

    private final List<FlagEvalEvent> staged = new ArrayList<>();

    /** Adds an event to the staged list (simulates hook enqueue). */
    void add(final FlagEvalEvent event) {
      staged.add(event);
    }

    /** Aggregates all staged events and returns the current aggregation state. */
    FlagEvaluationAggregator.AggregatedState drainAndAggregate() {
      for (final FlagEvalEvent e : staged) {
        aggregateEvent(e);
      }
      staged.clear();
      return aggregator.snapshot();
    }

    /** Simulates filling the full tier to GLOBAL_CAP by injecting synthetic distinct buckets. */
    void simulateFullTierAtCap() {
      aggregator.simulateFullTierAtCap();
    }

    /**
     * Simulates filling the degraded tier to DEGRADED_CAP by injecting synthetic distinct buckets.
     */
    void simulateDegradedTierAtCap() {
      aggregator.simulateDegradedTierAtCap();
    }

    void addDroppedDegradedOverflowForTest(final long count) {
      aggregator.droppedDegradedOverflow.addAndGet(count);
    }

    void addDegradedBucketForTest(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final String errorMessage,
        final long evalTimeMs) {
      aggregator.addDegradedBucketForTest(
          flagKey, variant, allocationKey, errorMessage, evalTimeMs);
    }

    void clearAggregationForTest() {
      aggregator.clear();
    }

    int fullTierSizeForTest() {
      return aggregator.fullTierSize();
    }
  }
}

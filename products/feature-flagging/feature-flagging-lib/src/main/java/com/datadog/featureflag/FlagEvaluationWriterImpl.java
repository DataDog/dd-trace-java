package com.datadog.featureflag;

import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_EVALUATION_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.BackendApiFactory;
import datadog.communication.EvpProxy;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.api.telemetry.CoreMetricCollector;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EVP {@code flagevaluation} writer for Java.
 *
 * <p>Uses the same EVP publisher path as {@link ExposureWriterImpl}, with two-tier aggregation
 * replacing the single-exposure buffer. Routes to the Agent-advertised EVP proxy endpoint for
 * {@code /api/v2/flagevaluation}.
 *
 * <p>Two-tier aggregation contract:
 *
 * <ul>
 *   <li>Two-tier aggregation: full -> degraded -> drop-counted.
 *   <li>Full key: (flagKey, variant, allocationKey, runtimeDefault, errorMessage, targetingKey,
 *       canonical-context-key).
 *   <li>Degraded key: (flagKey, variant, allocationKey, runtimeDefault, errorMessage) - no
 *       targetingKey/context.
 *   <li>Canonical context key: sorted entries, type-tagged length-delimited encoding - NOT a hash
 *       (collision-safe, comparable string identity).
 *   <li>Context pruning: deterministic (sort before cut), <=256 fields, string values <=256 chars;
 *       the pruned attributes are what gets aggregated and serialized.
 *   <li>Caps: globalCap=131072, perFlagCap=10000, degradedCap=32768.
 *   <li>Eval-time: min/max of firstEvalMs/lastEvalMs across events in the same bucket.
 *   <li>Runtime default: absent variant means {@code runtimeDefaultUsed=true}.
 *   <li>Flush interval: 10 seconds.
 *   <li>Queue: bounded MessagePassingBlockingQueue (capacity 2^16), non-blocking offer; on overflow
 *       the event is dropped and the {@code droppedQueueOverflow} counter is incremented and
 *       surfaced on flush.
 *   <li>Shutdown: {@link #close()} drains the queue and performs a final flush before the worker
 *       thread exits.
 * </ul>
 */
public class FlagEvaluationWriterImpl implements FlagEvaluationWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(FlagEvaluationWriterImpl.class);

  static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  static final int FLUSH_INTERVAL_SECONDS = 10;
  static final int EVAL_SCALE_FULL_BUCKET_TARGET =
      FlagEvaluationAggregator.EVAL_SCALE_FULL_BUCKET_TARGET;
  static final int EVAL_SCALE_PER_FLAG_BUCKET_TARGET =
      FlagEvaluationAggregator.EVAL_SCALE_PER_FLAG_BUCKET_TARGET;
  static final int EVAL_SCALE_DEGRADED_BUCKET_TARGET =
      FlagEvaluationAggregator.EVAL_SCALE_DEGRADED_BUCKET_TARGET;
  static final int GLOBAL_CAP = FlagEvaluationAggregator.GLOBAL_CAP;
  static final int PER_FLAG_CAP = FlagEvaluationAggregator.PER_FLAG_CAP;
  static final int DEGRADED_CAP = FlagEvaluationAggregator.DEGRADED_CAP;
  static final int FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES = EvpProxy.PAYLOAD_SIZE_LIMIT_BYTES;
  static final String FLAG_EVALUATION_DROPPED_METRIC = "flagevaluation.rows.dropped";
  static final String FLAG_EVALUATION_DEGRADED_METRIC = "flagevaluation.rows.degraded";
  static final String FLAG_EVALUATION_SPLITS_METRIC = "flagevaluation.payload.splits";
  static final String DROP_REASON_QUEUE_OVERFLOW = "queue_overflow";
  static final String DROP_REASON_CLOSED = "closed";
  static final String DROP_REASON_CONTEXT_ERROR = "context_error";
  static final String DROP_REASON_DEGRADED_CAP = "degraded_cap";
  static final String DROP_REASON_PAYLOAD_LIMIT = "payload_limit";
  static final String DEGRADED_REASON_CARDINALITY_CAP = "cardinality_cap";
  static final String DEGRADED_REASON_PAYLOAD_LIMIT = "payload_limit";
  private static final String FLAG_EVALUATION_ROUTE = "flagevaluation";
  private static final CoreMetricCollector CORE_METRICS = CoreMetricCollector.getInstance();

  // Context pruning limits: max fields and max string value length
  static final int MAX_CONTEXT_FIELDS = FlagEvaluationAggregator.MAX_CONTEXT_FIELDS;
  static final int MAX_FIELD_LENGTH = FlagEvaluationAggregator.MAX_FIELD_LENGTH;

  private final MessagePassingBlockingQueue<FlagEvalEvent> queue;
  private final FlagEvaluationSerializingHandler serializer;
  private final Thread serializerThread;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private static void countMetric(final String metricName, final long value, final String reason) {
    if (value <= 0) {
      return;
    }
    CORE_METRICS.count(metricName, value, reason == null ? null : "reason:" + reason);
  }

  /**
   * Observable counter for events dropped because the bounded hand-off queue was full when the hook
   * tried to enqueue (backpressure). Incremented on the hook thread, surfaced on flush.
   */
  private final AtomicLong droppedQueueOverflow = new AtomicLong(0);

  public FlagEvaluationWriterImpl(final SharedCommunicationObjects sco, final Config config) {
    this(DEFAULT_CAPACITY, FLUSH_INTERVAL_SECONDS, SECONDS, sco, config);
  }

  FlagEvaluationWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final SharedCommunicationObjects sco,
      final Config config) {
    this(capacity, flushInterval, timeUnit, new BackendApiFactory(config, sco), config);
  }

  /** Package-private constructor allowing a {@link BackendApiFactory} to be injected for tests. */
  FlagEvaluationWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final BackendApiFactory backendApiFactory,
      final Config config) {
    this.queue = Queues.mpscBlockingConsumerArrayQueue(capacity);
    this.serializer =
        new FlagEvaluationSerializingHandler(
            backendApiFactory,
            queue,
            flushInterval,
            timeUnit,
            FeatureFlagEvpContext.from(config),
            droppedQueueOverflow,
            this::close);
    this.serializerThread = newAgentThread(FEATURE_FLAG_EVALUATION_PROCESSOR, serializer);
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get()) {
        return;
      }
      // Register with the gateway so FlagEvalLoggingHook can route evaluations to this writer
      FeatureFlaggingGateway.setFlagEvalWriter(this);
      this.serializerThread.start();
    }
  }

  /** Test seam: starts the worker thread WITHOUT registering with the global gateway. */
  void startForTest() {
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
  public void close() {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      // Disable and deregister from the gateway so no new events are enqueued.
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
      FeatureFlaggingGateway.setFlagEvalWriter(null);
      if (!this.serializerThread.isAlive()) {
        return;
      }
      // Ask the worker to drain the queue and final-flush, then interrupt to wake it from poll().
      serializer.requestShutdown();
      this.serializerThread.interrupt();
    }
    if (Thread.currentThread() == this.serializerThread) {
      return;
    }
    try {
      // Bounded wait for the worker's final flush so queued events are not lost on shutdown.
      this.serializerThread.join(TimeUnit.SECONDS.toMillis(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void enqueue(final FlagEvalEvent event) {
    if (event == null) {
      return;
    }
    if (isClosedOrEnqueueDisabled()) {
      countClosedDropIfClosed();
      return;
    }
    synchronized (lifecycleLock) {
      if (isClosedOrEnqueueDisabled()) {
        countClosedDropIfClosed();
        return;
      }
      // Non-blocking offer. Count overflow so loss is observable rather than silent; the count is
      // surfaced on the next flush.
      if (!queue.offer(event)) {
        droppedQueueOverflow.incrementAndGet();
      }
    }
  }

  private boolean isClosedOrEnqueueDisabled() {
    return closed.get() || !FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled();
  }

  private void countClosedDropIfClosed() {
    if (closed.get()) {
      countMetric(FLAG_EVALUATION_DROPPED_METRIC, 1, DROP_REASON_CLOSED);
    }
  }

  /** Returns the count of events dropped due to queue-overflow backpressure (observable). */
  long droppedQueueOverflow() {
    return droppedQueueOverflow.get();
  }

  /** Test seam: returns one queued event without starting the worker. */
  FlagEvalEvent pollQueuedEventForTest() {
    return queue.poll();
  }

  /** Test seam: flushes serializer state without starting the worker. */
  void flushForTest() {
    serializer.flush();
  }

  // ---- Deterministic context pruning ----

  /**
   * Produces the deterministically-pruned context map used for both the canonical key and the
   * serialized payload. Keys are sorted FIRST, then the first {@link #MAX_CONTEXT_FIELDS}
   * non-oversized values are kept, so two logically-identical contexts always prune to the same
   * subset (and therefore the same canonical key). Oversized string values (>{@link
   * #MAX_FIELD_LENGTH} chars) are skipped. Returns an empty map for null/empty input.
   */
  static Map<String, Object> pruneContext(final Map<String, Object> attrs) {
    return FlagEvaluationAggregator.pruneContext(attrs);
  }

  // ---- Canonical context key ----
  // Sorted entries, type-tagged length-delimited encoding. NOT a hash (collision-safe string key).
  // Implementation mirrors dd-trace-go/openfeature/flagevaluation.go canonicalContextKey().

  /**
   * Builds the canonical context key from the already-pruned context map for the full-tier bucket
   * identity. Expects a pruned, sorted map (e.g. produced by {@link #pruneContext(Map)}); iterating
   * a {@link TreeMap} yields keys in sorted order, so the encoding is deterministic.
   *
   * <p>Returns an empty string for null/empty context.
   */
  static String canonicalContextKey(final Map<String, Object> prunedAttrs) {
    return FlagEvaluationAggregator.canonicalContextKey(prunedAttrs);
  }

  // ---- Serializing handler (background thread logic) ----

  static class FlagEvaluationSerializingHandler implements Runnable {
    private final MessagePassingBlockingQueue<FlagEvalEvent> queue;
    private final long ticksRequiredToFlush;

    @SuppressFBWarnings(
        value = "AT_NONATOMIC_64BIT_PRIMITIVE",
        justification = "the field is confined to the single serializer thread")
    private long lastTicks;

    private final FeatureFlagEvpPublisher<FlagEvaluationPayloads.FlagEvaluationsRequest>
        evpPublisher;
    final Map<String, String> context;
    private final AtomicLong droppedQueueOverflow;
    private final Runnable errorCallback;
    private final int payloadSizeLimitBytes;
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();

    // Shutdown coordination: set by close(), drives a final drain+flush before the worker exits.
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final CountDownLatch finalFlushDone = new CountDownLatch(1);

    FlagEvaluationSerializingHandler(
        final BackendApiFactory backendApiFactory,
        final MessagePassingBlockingQueue<FlagEvalEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final AtomicLong droppedQueueOverflow,
        final Runnable errorCallback) {
      this(
          backendApiFactory,
          queue,
          flushInterval,
          timeUnit,
          context,
          droppedQueueOverflow,
          errorCallback,
          FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES);
    }

    FlagEvaluationSerializingHandler(
        final BackendApiFactory backendApiFactory,
        final MessagePassingBlockingQueue<FlagEvalEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final AtomicLong droppedQueueOverflow,
        final Runnable errorCallback,
        final int payloadSizeLimitBytes) {
      this.queue = queue;
      this.evpPublisher =
          new FeatureFlagEvpPublisher<>(
              backendApiFactory, FlagEvaluationPayloads.FlagEvaluationsRequest.class, false);
      this.context = context;
      this.droppedQueueOverflow = droppedQueueOverflow;
      this.payloadSizeLimitBytes = payloadSizeLimitBytes;
      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);
      this.errorCallback = errorCallback;
      LOGGER.debug("starting flag evaluation serializer");
    }

    /** Signals the worker to drain the queue and perform a final flush before exiting. */
    void requestShutdown() {
      shutdownRequested.set(true);
    }

    @Override
    public void run() {
      if (!evpPublisher.start()) {
        finalFlushDone.countDown();
        errorCallback.run();
        throw new IllegalArgumentException("EVP Proxy not available");
      }
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        // On exit (interrupt or shutdown request), drain everything still buffered and flush it so
        // queued events are not lost on shutdown.
        drainAndFlush();
        finalFlushDone.countDown();
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

    /** Drains all remaining queued events and performs a final flush. Used on shutdown. */
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
        countMetric(FLAG_EVALUATION_DROPPED_METRIC, 1, DROP_REASON_CONTEXT_ERROR);
        LOGGER.debug("Could not aggregate flag evaluation event", e);
      }
    }

    // ---- Flush logic ----

    void flushIfNecessary() {
      if (aggregator.isEmpty() && droppedQueueOverflow.get() == 0) {
        return;
      }
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
      final long dgDrops = aggregator.droppedDegradedOverflow.getAndSet(0);
      countMetric(FLAG_EVALUATION_DROPPED_METRIC, dgDrops, DROP_REASON_DEGRADED_CAP);
      if (dgDrops > 0) {
        LOGGER.warn(
            "degraded aggregation tier full - dropped {} evaluation(s); raise degraded cap"
                + " (best-effort telemetry)",
            dgDrops);
      }

      if (aggregator.isEmpty()) {
        return;
      }
      boolean aggregatesWereEncoded = false;
      try {
        countMetric(
            FLAG_EVALUATION_DEGRADED_METRIC,
            aggregator.degradedEvaluationCount(),
            DEGRADED_REASON_CARDINALITY_CAP);
        final List<FlagEvaluationPayloads.FlagEvaluationEvent> events = buildEventList();
        if (events.isEmpty()) {
          return;
        }
        final FlagEvaluationPayloads.EncodedPayloads payloads =
            FlagEvaluationPayloads.buildPayloads(events, context, payloadSizeLimitBytes);
        aggregatesWereEncoded = true;
        countMetric(
            FLAG_EVALUATION_DROPPED_METRIC,
            payloads.droppedPayloadLimit,
            DROP_REASON_PAYLOAD_LIMIT);
        countMetric(
            FLAG_EVALUATION_DEGRADED_METRIC,
            payloads.degradedPayloadLimit,
            DEGRADED_REASON_PAYLOAD_LIMIT);
        if (payloads.bodies.size() > 1) {
          countMetric(FLAG_EVALUATION_SPLITS_METRIC, payloads.bodies.size() - 1, null);
        }
        if (payloads.droppedPayloadLimit > 0) {
          LOGGER.warn(
              "flag evaluation payload too large - dropped {} evaluation(s)"
                  + " (best-effort telemetry)",
              payloads.droppedPayloadLimit);
        }
        for (final byte[] payload : payloads.bodies) {
          evpPublisher.post(FLAG_EVALUATION_ROUTE, payload);
        }
      } catch (Exception e) {
        LOGGER.error("Could not submit flag evaluations", e);
      } finally {
        if (aggregatesWereEncoded) {
          // Once payload bytes are built, this writer is best-effort: clear encoded aggregates even
          // if one split post fails so a later flush cannot duplicate already-sent rows.
          aggregator.clear();
          lastTicks = System.nanoTime();
        }
      }
    }

    private List<FlagEvaluationPayloads.FlagEvaluationEvent> buildEventList() {
      final long flushTimeMs = System.currentTimeMillis();
      // Consent is read per bucket from the value captured at aggregation time, not from the
      // gateway here: CURRENT_CONFIG may have been overwritten by a later RC update since these
      // evaluations happened, and reading it at flush would apply the wrong environment's consent.
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

    SerializingHandlerForTest(final BackendApiFactory factory, final Map<String, String> context) {
      this(factory, context, FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES);
    }

    SerializingHandlerForTest(
        final BackendApiFactory factory,
        final Map<String, String> context,
        final int payloadSizeLimitBytes) {
      super(
          factory,
          Queues.mpscBlockingConsumerArrayQueue(DEFAULT_CAPACITY),
          Long.MAX_VALUE, // effectively never auto-flush
          TimeUnit.NANOSECONDS,
          context,
          new AtomicLong(0),
          () -> {},
          payloadSizeLimitBytes);
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

  /** Factory method for test use - creates a SerializingHandlerForTest. */
  static SerializingHandlerForTest createHandlerForTest(
      final BackendApiFactory factory, final Map<String, String> context) {
    return new SerializingHandlerForTest(factory, context);
  }

  static SerializingHandlerForTest createHandlerForTest(
      final BackendApiFactory factory,
      final Map<String, String> context,
      final int payloadSizeLimitBytes) {
    return new SerializingHandlerForTest(factory, context, payloadSizeLimitBytes);
  }
}

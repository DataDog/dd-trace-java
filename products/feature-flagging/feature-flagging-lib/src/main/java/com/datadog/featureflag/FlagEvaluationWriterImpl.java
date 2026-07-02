package com.datadog.featureflag;

import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_EVALUATION_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.api.telemetry.CoreMetricCollector;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EVP {@code flagevaluation} writer for Java.
 *
 * <p>Owns the bounded hook hand-off queue, background aggregation loop, and writer lifecycle. The
 * EVP payload/posting path is layered on top of this queue in the next review step.
 */
public class FlagEvaluationWriterImpl implements FlagEvaluationWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(FlagEvaluationWriterImpl.class);

  static final int DEFAULT_CAPACITY = 1 << 16;
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
  static final String FLAG_EVALUATION_DROPPED_METRIC = "flagevaluation.rows.dropped";
  static final String FLAG_EVALUATION_DEGRADED_METRIC = "flagevaluation.rows.degraded";
  static final String DROP_REASON_QUEUE_OVERFLOW = "queue_overflow";
  static final String DROP_REASON_DEGRADED_CAP = "degraded_cap";
  static final String DROP_REASON_CLOSED = "closed";
  static final String DROP_REASON_CONTEXT_ERROR = "context_error";
  static final String DEGRADED_REASON_CARDINALITY_CAP = "cardinality_cap";
  private static final CoreMetricCollector CORE_METRICS = CoreMetricCollector.getInstance();

  static final int MAX_CONTEXT_FIELDS = FlagEvaluationAggregator.MAX_CONTEXT_FIELDS;
  static final int MAX_FIELD_LENGTH = FlagEvaluationAggregator.MAX_FIELD_LENGTH;

  private final MessagePassingBlockingQueue<FlagEvalEvent> queue;
  private final FlagEvaluationSerializingHandler serializer;
  private final Thread serializerThread;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);

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

  FlagEvaluationWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final BackendApiFactory backendApiFactory,
      final Config config) {
    this.queue = Queues.mpscBlockingConsumerArrayQueue(capacity);
    this.serializer =
        new FlagEvaluationSerializingHandler(queue, flushInterval, timeUnit, droppedQueueOverflow);
    this.serializerThread = newAgentThread(FEATURE_FLAG_EVALUATION_PROCESSOR, serializer);
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get()) {
        return;
      }
      FeatureFlaggingGateway.setFlagEvalWriter(this);
      this.serializerThread.start();
    }
  }

  void startForTest() {
    synchronized (lifecycleLock) {
      if (!closed.get()) {
        this.serializerThread.start();
      }
    }
  }

  int aggregatorFullTierSizeForTest() {
    return serializer.aggregator.fullTierSize();
  }

  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      FeatureFlaggingGateway.setFlagEvalWriter(null);
      if (!this.serializerThread.isAlive()) {
        return;
      }
      serializer.requestShutdown();
      this.serializerThread.interrupt();
    }
    if (Thread.currentThread() == this.serializerThread) {
      return;
    }
    try {
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
    synchronized (lifecycleLock) {
      if (closed.get()) {
        countMetric(FLAG_EVALUATION_DROPPED_METRIC, 1, DROP_REASON_CLOSED);
        return;
      }
      if (!queue.offer(event)) {
        droppedQueueOverflow.incrementAndGet();
      }
    }
  }

  long droppedQueueOverflow() {
    return droppedQueueOverflow.get();
  }

  FlagEvalEvent pollQueuedEventForTest() {
    return queue.poll();
  }

  void flushForTest() {
    serializer.flush();
  }

  static Map<String, Object> pruneContext(final Map<String, Object> attrs) {
    return FlagEvaluationAggregator.pruneContext(attrs);
  }

  static String canonicalContextKey(final Map<String, Object> prunedAttrs) {
    return FlagEvaluationAggregator.canonicalContextKey(prunedAttrs);
  }

  private static void countMetric(final String metricName, final long value, final String reason) {
    if (value <= 0) {
      return;
    }
    CORE_METRICS.count(metricName, value, reason == null ? null : "reason:" + reason);
  }

  static class FlagEvaluationSerializingHandler implements Runnable {
    private final MessagePassingBlockingQueue<FlagEvalEvent> queue;
    private final long ticksRequiredToFlush;

    @SuppressFBWarnings(
        value = "AT_NONATOMIC_64BIT_PRIMITIVE",
        justification = "the field is confined to the single serializer thread")
    private long lastTicks;

    private final AtomicLong droppedQueueOverflow;
    final FlagEvaluationAggregator aggregator = new FlagEvaluationAggregator();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    FlagEvaluationSerializingHandler(
        final MessagePassingBlockingQueue<FlagEvalEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final AtomicLong droppedQueueOverflow) {
      this.queue = queue;
      this.droppedQueueOverflow = droppedQueueOverflow;
      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);
      LOGGER.debug("starting flag evaluation serializer");
    }

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
        drainAndFlush();
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

    void aggregateEvent(final FlagEvalEvent event) {
      try {
        aggregator.aggregate(event);
      } catch (LinkageError | RuntimeException e) {
        countMetric(FLAG_EVALUATION_DROPPED_METRIC, 1, DROP_REASON_CONTEXT_ERROR);
        LOGGER.debug("Could not aggregate flag evaluation event", e);
      }
    }

    void flushIfNecessary() {
      if (aggregator.isEmpty() && droppedQueueOverflow.get() == 0) {
        return;
      }
      if (shouldFlush()) {
        flush();
      }
    }

    void flush() {
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
      if (!aggregator.isEmpty()) {
        countMetric(
            FLAG_EVALUATION_DEGRADED_METRIC,
            aggregator.degradedEvaluationCount(),
            DEGRADED_REASON_CARDINALITY_CAP);
        aggregator.clear();
      }
      lastTicks = System.nanoTime();
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
}

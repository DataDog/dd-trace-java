package com.datadog.featureflag;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_EXPOSURE_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.BackendApiFactory;
import datadog.communication.EvpProxy;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.ExposuresRequest;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.telemetry.CoreMetricCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExposureWriterImpl implements ExposureWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposureWriterImpl.class);
  private static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  private static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  static final int MAX_BATCH_EVENTS = 1_000;
  static final int EXPOSURE_PAYLOAD_SIZE_LIMIT_BYTES = EvpProxy.PAYLOAD_SIZE_LIMIT_BYTES;
  static final String EXPOSURE_DROPPED_METRIC = "exposures.events.dropped";
  static final String DROP_REASON_QUEUE_OVERFLOW = "queue_overflow";
  static final String DROP_REASON_PAYLOAD_LIMIT = "payload_limit";
  static final String DROP_REASON_SERIALIZATION = "serialization";
  static final String DROP_REASON_DELIVERY_FAILURE = "delivery_failure";
  private static final String EXPOSURES_ROUTE = "exposures";
  private static final CoreMetricCollector CORE_METRICS = CoreMetricCollector.getInstance();

  private final MessagePassingBlockingQueue<ExposureEvent> queue;
  private final AtomicLong droppedQueueOverflow = new AtomicLong();
  private final ExposureSerializingHandler serializer;
  private final Thread serializerThread;

  private static void countDropped(final long value, final String reason) {
    CORE_METRICS.count(EXPOSURE_DROPPED_METRIC, value, "reason:" + reason);
  }

  public ExposureWriterImpl(final SharedCommunicationObjects sco, final Config config) {
    this(DEFAULT_CAPACITY, DEFAULT_FLUSH_INTERVAL_IN_SECONDS, SECONDS, sco, config);
  }

  ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final SharedCommunicationObjects sco,
      final Config config) {
    this.queue = Queues.mpscBlockingConsumerArrayQueue(capacity);
    this.serializer =
        new ExposureSerializingHandler(
            new BackendApiFactory(config, sco),
            queue,
            flushInterval,
            timeUnit,
            FeatureFlagEvpContext.from(config),
            droppedQueueOverflow,
            this::close);
    this.serializerThread = newAgentThread(FEATURE_FLAG_EXPOSURE_PROCESSOR, serializer);
  }

  @Override
  public void init() {
    FeatureFlaggingGateway.addExposureListener(this);
    this.serializerThread.start();
  }

  @Override
  public void close() {
    FeatureFlaggingGateway.removeExposureListener(this);
    if (this.serializerThread.isAlive()) {
      this.serializerThread.interrupt();
    }
  }

  @Override
  public void accept(final ExposureEvent event) {
    if (!queue.offer(event)) {
      droppedQueueOverflow.incrementAndGet();
    }
  }

  @VisibleForTesting
  boolean isSerializerThreadAlive() {
    return serializerThread.isAlive();
  }

  @VisibleForTesting
  int queueSize() {
    return queue.size();
  }

  @VisibleForTesting
  long droppedQueueOverflow() {
    return droppedQueueOverflow.get();
  }

  @VisibleForTesting
  void flushForTest() {
    serializer.flushIfNecessary();
  }

  private static class ExposureSerializingHandler implements Runnable {
    private final MessagePassingBlockingQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final FeatureFlagEvpPublisher<ExposuresRequest> evpPublisher;
    private final Map<String, String> context;
    private final ExposureCache cache;

    private final List<ExposureEvent> buffer = new ArrayList<>(MAX_BATCH_EVENTS);
    private final AtomicLong droppedQueueOverflow;
    private final Runnable errorCallback;

    public ExposureSerializingHandler(
        final BackendApiFactory backendApiFactory,
        final MessagePassingBlockingQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final AtomicLong droppedQueueOverflow,
        final Runnable errorCallback) {
      this.queue = queue;
      this.cache = new LRUExposureCache(queue.capacity());
      this.evpPublisher =
          new FeatureFlagEvpPublisher<>(
              backendApiFactory, ExposuresRequest.class, true, HttpRetryPolicy.Factory.NEVER_RETRY);
      this.context = context;
      this.droppedQueueOverflow = droppedQueueOverflow;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      this.errorCallback = errorCallback;

      LOGGER.debug("starting exposure serializer");
    }

    @Override
    public void run() {
      if (!evpPublisher.start()) {
        errorCallback.run();
        throw new IllegalArgumentException("EVP Proxy not available");
      }
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      LOGGER.debug("exposure processor worker exited. submitting exposures stopped.");
    }

    private void runDutyCycle() throws InterruptedException {
      final Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        ExposureEvent event;
        while ((event = queue.poll(100, TimeUnit.MILLISECONDS)) != null) {
          if (addToBuffer(event)) {
            consumeBatch();
            break;
          }
        }
        flushIfNecessary();
      }
    }

    private void consumeBatch() {
      final int remainingCapacity = MAX_BATCH_EVENTS - buffer.size();
      queue.drain(this::addToBuffer, Math.min(queue.size(), remainingCapacity));
    }

    /** Adds an element to the buffer taking care of duplicated exposures thanks to the LRU cache */
    private boolean addToBuffer(final ExposureEvent event) {
      if (cache.add(event)) {
        buffer.add(event);
        return true;
      }
      return false;
    }

    protected void flushIfNecessary() {
      reportQueueDrops();
      if (buffer.isEmpty()) {
        return;
      }
      if (shouldFlush()) {
        final ExposurePayloads.EncodingResult result;
        try {
          result =
              ExposurePayloads.writePayloads(
                  buffer, context, EXPOSURE_PAYLOAD_SIZE_LIMIT_BYTES, this::submitPayload);
        } finally {
          buffer.clear();
        }
        if (result.droppedSerialization > 0) {
          countDropped(result.droppedSerialization, DROP_REASON_SERIALIZATION);
          LOGGER.error(
              EXCLUDE_TELEMETRY,
              "Could not serialize {} exposure event(s); dropping events",
              result.droppedSerialization);
        }
        if (result.droppedPayloadLimit > 0) {
          countDropped(result.droppedPayloadLimit, DROP_REASON_PAYLOAD_LIMIT);
          LOGGER.warn(
              "Exposure payload limit dropped {} event(s) (best-effort telemetry)",
              result.droppedPayloadLimit);
        }
      }
    }

    private void submitPayload(final ExposurePayloads.EncodedPayload payload) {
      try {
        evpPublisher.post(EXPOSURES_ROUTE, payload.body);
      } catch (Exception e) {
        countDropped(payload.eventCount, DROP_REASON_DELIVERY_FAILURE);
        LOGGER.debug("Could not submit exposures; dropping attempted batch", e);
      }
    }

    private void reportQueueDrops() {
      final long dropped = droppedQueueOverflow.getAndSet(0);
      if (dropped > 0) {
        countDropped(dropped, DROP_REASON_QUEUE_OVERFLOW);
        LOGGER.warn(
            "Exposure queue full - dropped {} event(s) under backpressure"
                + " (best-effort telemetry)",
            dropped);
      }
    }

    private boolean shouldFlush() {
      long nanoTime = System.nanoTime();
      long ticks = nanoTime - lastTicks;
      if (ticks > ticksRequiredToFlush
          || buffer.size() >= MAX_BATCH_EVENTS
          || queue.size() >= FLUSH_THRESHOLD) {
        lastTicks = nanoTime;
        return true;
      }
      return false;
    }
  }
}

package com.datadog.featureflag;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.ExposuresRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class ExposurePipeline implements ExposureWriter {

  private static final Marker EXCLUDE_TELEMETRY = MarkerFactory.getMarker("EXCLUDE_TELEMETRY");
  private static final Logger LOGGER = LoggerFactory.getLogger(ExposurePipeline.class);
  private static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  private static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  private static final String EXPOSURES_ROUTE = "exposures";

  private final MessagePassingBlockingQueue<ExposureEvent> queue;
  private final Thread serializerThread;

  public ExposurePipeline(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final Supplier<EventTransport> transport,
      final Map<String, String> context,
      final RuntimeServices services) {
    this.queue = Queues.mpscBlockingConsumerArrayQueue(capacity);
    final ExposureSerializingHandler serializer =
        new ExposureSerializingHandler(
            transport, queue, flushInterval, timeUnit, context, this::close);
    this.serializerThread = services.newThread("exposures", serializer);
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
    queue.offer(event);
  }

  boolean isSerializerThreadAlive() {
    return serializerThread.isAlive();
  }

  int queueSize() {
    return queue.size();
  }

  private static class ExposureSerializingHandler implements Runnable {
    private final MessagePassingBlockingQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final EventPublisher<ExposuresRequest> evpPublisher;
    private final Map<String, String> context;
    private final ExposureCache cache;

    private final List<ExposureEvent> buffer = new ArrayList<>();
    private final Runnable errorCallback;

    ExposureSerializingHandler(
        final Supplier<EventTransport> transport,
        final MessagePassingBlockingQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final Runnable errorCallback) {
      this.queue = queue;
      this.cache = new LRUExposureCache(queue.capacity());
      this.evpPublisher = new EventPublisher<>(transport, ExposuresRequest.class);
      this.context = context;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      this.errorCallback = errorCallback;

      LOGGER.debug("starting exposure serializer");
    }

    @Override
    public void run() {
      if (!evpPublisher.start()) {
        errorCallback.run();
        LOGGER.warn("Feature Flagging exposure delivery is disabled");
        return;
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
      queue.drain(this::addToBuffer, queue.size());
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
      if (buffer.isEmpty()) {
        return;
      }
      if (shouldFlush()) {
        final byte[] payload;
        try {
          final ExposuresRequest exposures = new ExposuresRequest(this.context, this.buffer);
          payload = evpPublisher.serialize(exposures);
        } catch (RuntimeException e) {
          LOGGER.error(EXCLUDE_TELEMETRY, "Could not serialize exposures; dropping batch", e);
          this.buffer.clear();
          return;
        }
        try {
          evpPublisher.post(EXPOSURES_ROUTE, payload);
        } catch (Exception e) {
          LOGGER.debug("Could not submit exposures", e);
        } finally {
          // Best-effort delivery must not retry an ambiguously accepted batch. A later definitive
          // proxy rejection could otherwise replay the same exposures through direct intake.
          this.buffer.clear();
        }
      }
    }

    private boolean shouldFlush() {
      long nanoTime = System.nanoTime();
      long ticks = nanoTime - lastTicks;
      if (ticks > ticksRequiredToFlush || queue.size() >= FLUSH_THRESHOLD) {
        lastTicks = nanoTime;
        return true;
      }
      return false;
    }
  }
}

package com.datadog.featureflag;

import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_EXPOSURE_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.ExposuresRequest;
import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExposureWriterImpl implements ExposureWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposureWriterImpl.class);
  private static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  private static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  private static final String EXPOSURES_ROUTE = "exposures";

  private final MessagePassingBlockingQueue<ExposureEvent> queue;
  private final Thread serializerThread;

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
    final ExposureSerializingHandler serializer =
        new ExposureSerializingHandler(
            new BackendApiFactory(config, sco),
            queue,
            flushInterval,
            timeUnit,
            FeatureFlagEvpContext.from(config),
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
    queue.offer(event);
  }

  @VisibleForTesting
  boolean isSerializerThreadAlive() {
    return serializerThread.isAlive();
  }

  @VisibleForTesting
  int queueSize() {
    return queue.size();
  }

  private static class ExposureSerializingHandler implements Runnable {
    private final MessagePassingBlockingQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final FeatureFlagEvpPublisher<ExposuresRequest> evpPublisher;
    private final Map<String, String> context;
    private final ExposureCache cache;

    private final List<ExposureEvent> buffer = new ArrayList<>();
    private final Runnable errorCallback;

    public ExposureSerializingHandler(
        final BackendApiFactory backendApiFactory,
        final MessagePassingBlockingQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final Runnable errorCallback) {
      this.queue = queue;
      this.cache = new LRUExposureCache(queue.capacity());
      this.evpPublisher = new FeatureFlagEvpPublisher<>(backendApiFactory, ExposuresRequest.class);
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
        try {
          final ExposuresRequest exposures = new ExposuresRequest(this.context, this.buffer);
          evpPublisher.post(EXPOSURES_ROUTE, exposures);
          this.buffer.clear();
        } catch (Exception e) {
          LOGGER.error("Could not submit exposures", e);
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

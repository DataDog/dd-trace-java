package com.datadog.featureflag;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_EXPOSURE_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.BackendApi;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExposureWriterImpl implements ExposureWriter, FeatureFlagEventWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposureWriterImpl.class);
  private static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  private static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  private static final String EXPOSURES_ROUTE = "exposures";
  static final long SHUTDOWN_TIMEOUT_MILLIS = THREAD_JOIN_TIMOUT_MS;

  private final MessagePassingBlockingQueue<ExposureEvent> queue;
  private final ExposureSerializingHandler serializer;
  private final Thread serializerThread;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final long shutdownTimeoutMillis;

  ExposureWriterImpl(
      final SharedCommunicationObjects sco,
      final Config config,
      final FeatureFlagRouteSelector routeSelector) {
    this(
        DEFAULT_CAPACITY,
        DEFAULT_FLUSH_INTERVAL_IN_SECONDS,
        SECONDS,
        new FeatureFlagBackendApiFactory(config, sco, FeatureFlagEventType.EXPOSURE, routeSelector),
        config);
  }

  ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final SharedCommunicationObjects sco,
      final Config config) {
    this(
        capacity,
        flushInterval,
        timeUnit,
        new FeatureFlagBackendApiFactory(config, sco, FeatureFlagEventType.EXPOSURE),
        config);
  }

  ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final FeatureFlagBackendApiFactory backendApiFactory,
      final Config config) {
    this(
        capacity,
        flushInterval,
        timeUnit,
        backendApiFactory::create,
        config,
        SHUTDOWN_TIMEOUT_MILLIS);
  }

  ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final Supplier<BackendApi> backendApiSupplier,
      final Config config,
      final long shutdownTimeoutMillis) {
    this(
        Queues.mpscBlockingConsumerArrayQueue(capacity),
        flushInterval,
        timeUnit,
        backendApiSupplier,
        config,
        shutdownTimeoutMillis);
  }

  @VisibleForTesting
  ExposureWriterImpl(
      final MessagePassingBlockingQueue<ExposureEvent> queue,
      final long flushInterval,
      final TimeUnit timeUnit,
      final Supplier<BackendApi> backendApiSupplier,
      final Config config,
      final long shutdownTimeoutMillis) {
    this.queue = queue;
    this.serializer =
        new ExposureSerializingHandler(
            backendApiSupplier,
            queue,
            flushInterval,
            timeUnit,
            FeatureFlagEvpContext.from(config),
            this::close);
    this.serializerThread = newAgentThread(FEATURE_FLAG_EXPOSURE_PROCESSOR, serializer);
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
  }

  @Override
  public void init() {
    synchronized (lifecycleLock) {
      if (closed.get()) {
        return;
      }
      FeatureFlaggingGateway.addExposureListener(this);
      this.serializerThread.start();
    }
  }

  @Override
  public void close() {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMillis);
    requestShutdown();
    awaitTermination(deadline);
  }

  @Override
  public void requestShutdown() {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      // Stop accepting new dispatches without waiting for producers already inside accept().
      FeatureFlaggingGateway.removeExposureListener(this);
      // Polling wakes within 100 ms. An interrupt can race with, or abort, the final POST.
      serializer.requestShutdown();
    }
  }

  @Override
  public void awaitTermination(final long deadlineNanos) {
    FeatureFlagEventWriter.awaitThread(serializerThread, deadlineNanos);
  }

  @Override
  public void accept(final ExposureEvent event) {
    // Keep producers lock-free, like flag evaluations. Shutdown is best effort: an offer that
    // passes this check can race past the final drain and be dropped. Stale gateway dispatches
    // entering after shutdown starts are rejected without extending the shutdown wait.
    if (!closed.get()) {
      queue.offer(event);
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

  private static class ExposureSerializingHandler implements Runnable {
    private final MessagePassingBlockingQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final FeatureFlagEvpPublisher<ExposuresRequest> evpPublisher;
    private final Map<String, String> context;
    private final ExposureCache cache;

    private final List<ExposureEvent> buffer = new ArrayList<>();
    private final Runnable errorCallback;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    ExposureSerializingHandler(
        final Supplier<BackendApi> backendApiSupplier,
        final MessagePassingBlockingQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final Runnable errorCallback) {
      this.queue = queue;
      this.cache = new LRUExposureCache(queue.capacity());
      this.evpPublisher = new FeatureFlagEvpPublisher<>(backendApiSupplier, ExposuresRequest.class);
      this.context = context;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      this.errorCallback = errorCallback;

      LOGGER.debug("starting exposure serializer");
    }

    void requestShutdown() {
      shutdownRequested.set(true);
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
      } finally {
        // Preserve an unrelated interrupt without passing it into OkHttp's final best-effort send.
        final boolean wasInterrupted = Thread.interrupted();
        try {
          drainAndFlush();
        } finally {
          if (wasInterrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
      LOGGER.debug("exposure processor worker exited. submitting exposures stopped.");
    }

    private void runDutyCycle() throws InterruptedException {
      final Thread thread = Thread.currentThread();
      while (!thread.isInterrupted() && !shutdownRequested.get()) {
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

    private void drainAndFlush() {
      ExposureEvent event;
      while ((event = queue.poll()) != null) {
        addToBuffer(event);
      }
      flush();
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
      if (!buffer.isEmpty() && shouldFlush()) {
        flush();
      }
    }

    private void flush() {
      if (buffer.isEmpty()) {
        return;
      }
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
      } catch (AgentlessFeatureFlagBackendApi.UnavailableRouteException ignored) {
        // The shared selector reports unavailable/recovered transitions once for both writers.
      } catch (Exception e) {
        LOGGER.debug("Could not submit exposures", e);
      } finally {
        // Best-effort delivery must not retry an ambiguously accepted batch. A later definitive
        // proxy rejection could otherwise replay the same exposures through direct intake.
        this.buffer.clear();
      }
    }

    private boolean shouldFlush() {
      long nanoTime = System.nanoTime();
      long ticks = nanoTime - lastTicks;
      if (ticks > ticksRequiredToFlush || buffer.size() >= FLUSH_THRESHOLD) {
        lastTicks = nanoTime;
        return true;
      }
      return false;
    }
  }
}

package com.datadog.featureflag;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_EXPOSURE_PROCESSOR;
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
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExposureWriterImpl implements ExposureWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposureWriterImpl.class);
  private static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  private static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  private static final String EXPOSURES_ROUTE = "exposures";
  static final long SHUTDOWN_TIMEOUT_MILLIS = SECONDS.toMillis(5);

  private final MessagePassingBlockingQueue<ExposureEvent> queue;
  private final ExposureSerializingHandler serializer;
  private final Thread serializerThread;
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final long shutdownTimeoutMillis;

  public ExposureWriterImpl(final SharedCommunicationObjects sco, final Config config) {
    this(DEFAULT_CAPACITY, DEFAULT_FLUSH_INTERVAL_IN_SECONDS, SECONDS, sco, config);
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
    this.queue = Queues.mpscBlockingConsumerArrayQueue(capacity);
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
    lifecycleLock.writeLock().lock();
    try {
      if (closed.get()) {
        return;
      }
      FeatureFlaggingGateway.addExposureListener(this);
      this.serializerThread.start();
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  @Override
  public void close() {
    final boolean workerRunning;
    lifecycleLock.writeLock().lock();
    try {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      // Exclude all producers before asking the single consumer to perform its final drain.
      FeatureFlaggingGateway.removeExposureListener(this);
      workerRunning = this.serializerThread.isAlive();
      if (workerRunning) {
        serializer.requestShutdown();
        this.serializerThread.interrupt();
      }
    } finally {
      lifecycleLock.writeLock().unlock();
    }

    // start() failure invokes close() from the serializer itself. It cannot join itself, and no
    // final flush is possible when no backend route was created.
    if (!workerRunning || Thread.currentThread() == this.serializerThread) {
      return;
    }
    try {
      // Bound application shutdown even if the final best-effort network request does not return.
      this.serializerThread.join(shutdownTimeoutMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void accept(final ExposureEvent event) {
    // Multiple producers may still enqueue concurrently under the read lock. close() takes the
    // write lock, so every accepted event is in the queue before the worker's final drain begins,
    // while stale CopyOnWriteArrayList dispatch snapshots are rejected after shutdown starts.
    lifecycleLock.readLock().lock();
    try {
      if (!closed.get()) {
        queue.offer(event);
      }
    } finally {
      lifecycleLock.readLock().unlock();
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
        // close() interrupts this thread to wake queue.poll(). OkHttp fails fast when the calling
        // thread is interrupted, so clear the flag for the final best-effort drain and restore it
        // only after the final send-once attempt has completed.
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

package com.datadog.openfeature.internal.exposure;

import static com.datadog.openfeature.internal.EventPipeline.awaitThread;
import static com.datadog.openfeature.internal.exposure.ExposuresRequest.writeExposure;

import com.datadog.openfeature.internal.EventPipeline;
import com.datadog.openfeature.internal.EventQueue;
import com.datadog.openfeature.internal.JsonArrayPayload;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Batches exposure events to the event platform {@code exposures} route. */
public class ExposurePipeline implements Consumer<ExposureEvent>, EventPipeline {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposurePipeline.class);
  public static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  public static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  // The largest payload the event platform intake accepts, as for flag evaluations.
  static final int PAYLOAD_SIZE_LIMIT_BYTES = 5 * 1024 * 1024;
  private static final String EXPOSURES_ROUTE = "exposures";

  private final EventQueue<ExposureEvent> queue;
  private final ExposureSerializingHandler serializer;
  private final Thread serializerThread;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong droppedQueueFull = new AtomicLong();

  public ExposurePipeline(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final EventTransport transport,
      final Map<String, String> context,
      final RuntimeServices services) {
    this(capacity, flushInterval, timeUnit, transport, context, services, PAYLOAD_SIZE_LIMIT_BYTES);
  }

  /** Lets tests use a small payload size limit. */
  ExposurePipeline(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final EventTransport transport,
      final Map<String, String> context,
      final RuntimeServices services,
      final int payloadSizeLimitBytes) {
    this.queue = new EventQueue<>(capacity);
    this.serializer =
        new ExposureSerializingHandler(
            transport,
            this.queue,
            flushInterval,
            timeUnit,
            context,
            this.droppedQueueFull,
            payloadSizeLimitBytes);
    this.serializerThread = services.newThread("exposures", this.serializer);
  }

  /** Starts the background serializing thread. */
  public void start() {
    synchronized (this.lifecycleLock) {
      if (!this.closed.get()) {
        this.serializerThread.start();
      }
    }
  }

  @Override
  public void requestShutdown() {
    synchronized (this.lifecycleLock) {
      if (this.closed.compareAndSet(false, true)) {
        final int queued = this.queue.size();
        this.serializer.requestShutdown();
        // Logged last: a diagnostic must not keep the worker from being signaled.
        LOGGER.debug(
            "Feature Flags exposure shutdown requested: queued={}; pending exposures get a final"
                + " best-effort flush",
            queued);
      }
    }
  }

  @Override
  public void awaitTermination(final long deadlineNanos) {
    awaitThread(this.serializerThread, deadlineNanos);
  }

  @Override
  public void accept(final ExposureEvent event) {
    // Runs on the application thread: count the drops, the serializer reports them once per flush.
    if (!this.closed.get() && !this.queue.offer(event)) {
      this.droppedQueueFull.incrementAndGet();
    }
  }

  boolean isSerializerThreadAlive() {
    return serializerThread.isAlive();
  }

  int queueSize() {
    return queue.size();
  }

  private static class ExposureSerializingHandler implements Runnable {
    private final EventQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final EventTransport transport;

    // The exposures since the last flush, serialized as they are drained, so a batch waits for its
    // flush as compact bytes in reused chunks rather than as event objects. A full payload is sent
    // at once.
    private final JsonArrayPayload payload;
    private final Consumer<ExposureEvent> add = this::add;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicLong droppedQueueFull;

    ExposureSerializingHandler(
        final EventTransport transport,
        final EventQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context,
        final AtomicLong droppedQueueFull,
        final int payloadSizeLimitBytes) {
      this.droppedQueueFull = droppedQueueFull;
      this.queue = queue;
      this.transport = transport;

      this.payload =
          new JsonArrayPayload(
              ExposuresRequest.prefix(context), ExposuresRequest.SUFFIX, payloadSizeLimitBytes);
      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      LOGGER.debug("starting exposure serializer");
    }

    void requestShutdown() {
      this.shutdownRequested.set(true);
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
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
      while (!thread.isInterrupted() && !this.shutdownRequested.get()) {
        // The provider already deduplicated the exposures.
        final ExposureEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
        if (event != null) {
          add(event);
          consumeBatch();
        }
        flushIfNecessary();
      }
    }

    private void consumeBatch() {
      queue.drain(this.add, queue.size());
    }

    private void drainAndFlush() {
      ExposureEvent event;
      while ((event = this.queue.poll()) != null) {
        add(event);
      }
      flush();
    }

    /** Serializes an exposure into the payload, sending the payload first if it is full. */
    private void add(final ExposureEvent event) {
      if (append(event)) {
        return;
      }
      if (this.payload.count() > 0) {
        flush();
        if (append(event)) {
          return;
        }
      }
      LOGGER.debug("Feature Flags exposure dropped: larger than a payload");
    }

    /**
     * @return whether the exposure was handled: serialized, or dropped as unserializable.
     */
    private boolean append(final ExposureEvent event) {
      try {
        return this.payload.append(generator -> writeExposure(generator, event));
      } catch (final RuntimeException e) {
        LOGGER.error("Could not serialize an exposure; dropping it", e);
        return true;
      }
    }

    protected void flushIfNecessary() {
      if (this.payload.count() > 0 && shouldFlush()) {
        flush();
      }
    }

    private void flush() {
      final long dropped = this.droppedQueueFull.getAndSet(0);
      if (dropped > 0) {
        LOGGER.debug(
            "Feature Flags exposures dropped since the last flush: queue full, count={}", dropped);
      }
      final int count = this.payload.count();
      if (count == 0) {
        return;
      }
      LOGGER.debug("Feature Flags exposure flush starting: count={}", count);
      // Best-effort delivery must not retry an ambiguously accepted batch: the payload is emptied
      // whether or not the post succeeds.
      try {
        this.transport.post(EXPOSURES_ROUTE, this.payload.body());
        this.payload.reset();
        LOGGER.debug("Feature Flags exposure flush delivered: count={}", count);
      } catch (Exception e) {
        this.payload.discard(); // a timed-out request may still be reading the old buffers
        LOGGER.debug(
            "Feature Flags exposure flush failed: {}; dropping the batch of {} exposure(s)",
            e.toString(),
            count,
            e);
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

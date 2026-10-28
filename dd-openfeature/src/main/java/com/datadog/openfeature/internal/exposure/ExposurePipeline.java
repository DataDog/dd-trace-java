package com.datadog.openfeature.internal.exposure;

import static com.datadog.openfeature.internal.EventPipeline.awaitThread;

import com.datadog.openfeature.internal.EventPipeline;
import com.datadog.openfeature.internal.EventQueue;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Batches exposure events to the event platform {@code exposures} route. */
public class ExposurePipeline implements Consumer<ExposureEvent>, EventPipeline {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposurePipeline.class);
  public static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  public static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  private static final String EXPOSURES_ROUTE = "exposures";

  private final EventQueue<ExposureEvent> queue;
  private final ExposureSerializingHandler serializer;
  private final Thread serializerThread;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();

  public ExposurePipeline(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final EventTransport transport,
      final Map<String, String> context,
      final RuntimeServices services) {
    this.queue = new EventQueue<>(capacity);
    this.serializer =
        new ExposureSerializingHandler(transport, this.queue, flushInterval, timeUnit, context);
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
        this.serializer.requestShutdown();
      }
    }
  }

  @Override
  public void awaitTermination(final long deadlineNanos) {
    awaitThread(this.serializerThread, deadlineNanos);
  }

  @Override
  public void accept(final ExposureEvent event) {
    if (!this.closed.get()) {
      this.queue.offer(event);
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
    private final Map<String, String> context;

    private final List<ExposureEvent> buffer = new ArrayList<>();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();

    ExposureSerializingHandler(
        final EventTransport transport,
        final EventQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context) {
      this.queue = queue;
      this.transport = transport;
      this.context = context;

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
          this.buffer.add(event);
          consumeBatch();
        }
        flushIfNecessary();
      }
    }

    private void consumeBatch() {
      queue.drain(this.buffer::add, queue.size());
    }

    private void drainAndFlush() {
      ExposureEvent event;
      while ((event = this.queue.poll()) != null) {
        this.buffer.add(event);
      }
      flush();
    }

    protected void flushIfNecessary() {
      if (!this.buffer.isEmpty() && shouldFlush()) {
        flush();
      }
    }

    private void flush() {
      if (this.buffer.isEmpty()) {
        return;
      }
      final byte[] payload;
      try {
        final ExposuresRequest exposures = new ExposuresRequest(this.context, this.buffer);
        payload = exposures.serialize();
      } catch (RuntimeException e) {
        LOGGER.error("Could not serialize exposures; dropping batch", e);
        this.buffer.clear();
        return;
      }
      try {
        this.transport.post(EXPOSURES_ROUTE, payload);
      } catch (Exception e) {
        LOGGER.debug("Could not submit exposures", e);
      } finally {
        // Best-effort delivery must not retry an ambiguously accepted batch.
        this.buffer.clear();
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

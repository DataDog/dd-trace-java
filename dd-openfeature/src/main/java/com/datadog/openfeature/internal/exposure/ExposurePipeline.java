package com.datadog.openfeature.internal.exposure;

import com.datadog.openfeature.internal.EventQueue;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.connector.EventTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deduplicates and batches exposure events to the event platform {@code exposures} route. */
public class ExposurePipeline implements Consumer<ExposureEvent>, AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposurePipeline.class);
  public static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  public static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;
  private static final String EXPOSURES_ROUTE = "exposures";

  private final EventQueue<ExposureEvent> queue;
  private final Thread serializerThread;

  public ExposurePipeline(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final EventTransport transport,
      final Map<String, String> context,
      final RuntimeServices services) {
    this.queue = new EventQueue<>(capacity);
    final ExposureSerializingHandler serializer =
        new ExposureSerializingHandler(transport, queue, flushInterval, timeUnit, context);
    this.serializerThread = services.newThread("exposures", serializer);
  }

  /** Starts the background serializing thread. */
  public void start() {
    this.serializerThread.start();
  }

  @Override
  public void close() {
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
    private final EventQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final EventTransport transport;
    private final Map<String, String> context;
    private final ExposureCache cache;

    private final List<ExposureEvent> buffer = new ArrayList<>();

    ExposureSerializingHandler(
        final EventTransport transport,
        final EventQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context) {
      this.queue = queue;
      this.cache = new LRUExposureCache(queue.capacity());
      this.transport = transport;
      this.context = context;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      LOGGER.debug("starting exposure serializer");
    }

    @Override
    public void run() {
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
          payload = exposures.serialize();
        } catch (RuntimeException e) {
          LOGGER.error("Could not serialize exposures; dropping batch", e);
          this.buffer.clear();
          return;
        }
        try {
          transport.post(EXPOSURES_ROUTE, payload);
        } catch (Exception e) {
          LOGGER.debug("Could not submit exposures", e);
        } finally {
          // Best-effort delivery must not retry an ambiguously accepted batch.
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

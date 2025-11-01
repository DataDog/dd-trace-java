package datadog.trace.common.writer;

import datadog.trace.api.Config;
import java.io.Closeable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages retry of trace payloads that receive a 429 (Too Many Requests) response from the agent.
 * Uses a bounded queue and exponential backoff strategy.
 */
public class TraceRetryManager implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(TraceRetryManager.class);

  private final BlockingQueue<RetryEntry> retryQueue;
  private final RemoteApi api;
  private final Config config;
  private final Thread retryWorker;
  private volatile boolean running;

  // Telemetry counters
  private final AtomicLong retriesEnqueued = new AtomicLong(0);
  private final AtomicLong retriesDroppedQueueFull = new AtomicLong(0);
  private final AtomicLong retriesResubmitted = new AtomicLong(0);
  private final AtomicLong retriesExhausted = new AtomicLong(0);
  private final AtomicLong retries429Count = new AtomicLong(0);

  public TraceRetryManager(RemoteApi api, Config config) {
    this.api = api;
    this.config = config;
    this.retryQueue = new LinkedBlockingQueue<>(config.getRetryQueueSize());
    this.retryWorker = new Thread(this::processRetries, "dd-trace-retry-worker");
    this.retryWorker.setDaemon(true);
    this.running = false;
  }

  /** Start the retry worker thread */
  public void start() {
    if (!running) {
      running = true;
      retryWorker.start();
      log.debug("TraceRetryManager started with queue size {}", config.getRetryQueueSize());
    }
  }

  /**
   * Enqueue a payload for retry with exponential backoff
   *
   * @param payload the payload to retry
   * @param attemptCount the number of previous attempts (0 for first retry)
   */
  public void enqueue(Payload payload, int attemptCount) {
    long backoffMs = calculateBackoff(attemptCount);
    long retryAfterMs = System.currentTimeMillis() + backoffMs;
    RetryEntry entry = new RetryEntry(payload, retryAfterMs, attemptCount);

    if (!retryQueue.offer(entry)) {
      retriesDroppedQueueFull.incrementAndGet();
      log.warn(
          "Retry queue full (size={}), dropping payload with {} traces",
          config.getRetryQueueSize(),
          payload.traceCount());
    } else {
      retriesEnqueued.incrementAndGet();
      log.debug(
          "Enqueued payload for retry with backoff {}ms (attempt {}, {} traces)",
          backoffMs,
          attemptCount + 1,
          payload.traceCount());
    }
  }

  /**
   * Calculate exponential backoff delay
   *
   * @param attemptCount the number of previous attempts
   * @return backoff delay in milliseconds
   */
  private long calculateBackoff(int attemptCount) {
    long backoff = config.getRetryBackoffInitialMs() * (1L << attemptCount);
    return Math.min(backoff, config.getRetryBackoffMaxMs());
  }

  /** Worker loop that drains the retry queue and resubmits payloads */
  private void processRetries() {
    log.debug("Retry worker thread started");
    while (running) {
      try {
        RetryEntry entry = retryQueue.poll(1, TimeUnit.SECONDS);
        if (entry != null) {
          long now = System.currentTimeMillis();
          if (now < entry.retryAfterMs) {
            // Not ready yet, sleep and re-enqueue
            long sleepMs = entry.retryAfterMs - now;
            Thread.sleep(sleepMs);
          }
          resubmit(entry);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("Retry worker interrupted");
        break;
      }
    }
    log.debug("Retry worker thread stopped");
  }

  /**
   * Resubmit a payload from the retry queue
   *
   * @param entry the retry entry to resubmit
   */
  private void resubmit(RetryEntry entry) {
    try {
      RemoteApi.Response response = api.sendSerializedTraces(entry.payload);

      if (response.retryable()) {
        // Still getting 429, re-enqueue with increased backoff
        retries429Count.incrementAndGet();
        log.debug(
            "Retry received another 429 for {} traces (attempt {})",
            entry.payload.traceCount(),
            entry.attemptCount + 1);
        enqueue(entry.payload, entry.attemptCount + 1);
      } else if (response.success()) {
        // Success!
        retriesResubmitted.incrementAndGet();
        log.debug(
            "Retry successful for {} traces after {} attempts",
            entry.payload.traceCount(),
            entry.attemptCount + 1);
      } else {
        // Other error, drop and log
        retriesExhausted.incrementAndGet();
        log.warn(
            "Retry exhausted for {} traces with status {} after {} attempts",
            entry.payload.traceCount(),
            response.status().isPresent() ? response.status().getAsInt() : "unknown",
            entry.attemptCount + 1);
      }
    } catch (Exception e) {
      retriesExhausted.incrementAndGet();
      log.warn(
          "Exception during retry of {} traces after {} attempts",
          entry.payload.traceCount(),
          entry.attemptCount + 1,
          e);
    }
  }

  @Override
  public void close() {
    log.debug("Shutting down TraceRetryManager");
    running = false;
    retryWorker.interrupt();
    try {
      retryWorker.join(5000); // Wait up to 5 seconds for graceful shutdown
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for retry worker to stop");
    }

    // Log final telemetry
    log.info(
        "TraceRetryManager closed. Stats: enqueued={}, resubmitted={}, dropped_queue_full={}, exhausted={}, http_429={}",
        retriesEnqueued.get(),
        retriesResubmitted.get(),
        retriesDroppedQueueFull.get(),
        retriesExhausted.get(),
        retries429Count.get());
  }

  // Telemetry accessors for testing and monitoring
  public long getRetriesEnqueued() {
    return retriesEnqueued.get();
  }

  public long getRetriesDroppedQueueFull() {
    return retriesDroppedQueueFull.get();
  }

  public long getRetriesResubmitted() {
    return retriesResubmitted.get();
  }

  public long getRetriesExhausted() {
    return retriesExhausted.get();
  }

  public long getRetries429Count() {
    return retries429Count.get();
  }

  public int getQueueSize() {
    return retryQueue.size();
  }

  /** Internal class to hold retry state for a payload */
  private static class RetryEntry {
    final Payload payload;
    final long retryAfterMs;
    final int attemptCount;

    RetryEntry(Payload payload, long retryAfterMs, int attemptCount) {
      this.payload = payload;
      this.retryAfterMs = retryAfterMs;
      this.attemptCount = attemptCount;
    }
  }
}

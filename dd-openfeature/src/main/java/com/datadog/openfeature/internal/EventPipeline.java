package com.datadog.openfeature.internal;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * An event pipeline stopping in two phases, so that several pipelines can share one shutdown
 * budget: every pipeline is first asked to flush, then each one is awaited until the same deadline.
 */
public interface EventPipeline extends AutoCloseable {
  /** The time the pipelines get to flush their pending events when stopping. */
  long SHUTDOWN_TIMEOUT_MILLIS = 800;

  /** Stops accepting events and asks the worker to flush the pending ones, without waiting. */
  void requestShutdown();

  /**
   * Waits for the worker to flush the pending events and exit.
   *
   * @param deadlineNanos the {@link System#nanoTime()} value to wait until.
   */
  void awaitTermination(long deadlineNanos);

  /** Stops the pipeline, waiting at most {@link #SHUTDOWN_TIMEOUT_MILLIS} for the final flush. */
  @Override
  default void close() {
    final long deadline = shutdownDeadline();
    requestShutdown();
    awaitTermination(deadline);
  }

  /**
   * @return the deadline of a shutdown starting now.
   */
  static long shutdownDeadline() {
    return System.nanoTime() + MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MILLIS);
  }

  /**
   * Waits for a worker thread to exit.
   *
   * @param worker the worker thread.
   * @param deadlineNanos the {@link System#nanoTime()} value to wait until.
   */
  static void awaitThread(final Thread worker, final long deadlineNanos) {
    final long remaining = deadlineNanos - System.nanoTime();
    if (worker == Thread.currentThread() || remaining <= 0) {
      return;
    }
    try {
      NANOSECONDS.timedJoin(worker, remaining);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

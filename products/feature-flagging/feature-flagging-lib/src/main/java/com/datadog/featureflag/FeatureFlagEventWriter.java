package com.datadog.featureflag;

import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/** Internal two-phase shutdown for writers sharing one subsystem termination budget. */
interface FeatureFlagEventWriter extends AutoCloseable {
  void requestShutdown();

  void awaitTermination(long deadlineNanos);

  static long shutdownDeadline() {
    return System.nanoTime() + MILLISECONDS.toNanos(THREAD_JOIN_TIMOUT_MS);
  }

  static void awaitThread(Thread worker, long deadlineNanos) {
    final long remaining = deadlineNanos - System.nanoTime();
    if (worker == Thread.currentThread() || remaining <= 0) {
      return;
    }
    try {
      // timedJoin preserves sub-millisecond budgets; never turn an expired budget into join(0).
      NANOSECONDS.timedJoin(worker, remaining);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

package datadog.trace.api.time;

import java.util.concurrent.TimeUnit;

/**
 * Emulates nanosecond precision by combining ticks and timestamps
 */
class Java7TimeSource implements TimeSource {
  // currentTimeMillis() and nanoTime() drift
  // This class resets the drift at this interval
  private static final long DRIFT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(90);
  private long startMillis;
  private long startNanos;

  Java7TimeSource() {
    startMillis = System.currentTimeMillis();
    startNanos = System.nanoTime();
  }

  @Override
  public long getNanoTicks() {
    return System.nanoTime();
  }

  @Override
  public long getCurrentTimeMillis() {
    return System.currentTimeMillis();
  }

  @Override
  public long getCurrentTimeMicros() {
    return TimeUnit.NANOSECONDS.toMicros(getCurrentTimeNanos());
  }

  @Override
  public long getCurrentTimeNanos() {
    long currentNanoTime = System.nanoTime();
    if (currentNanoTime > startNanos + DRIFT_INTERVAL_NANOS) {
      currentNanoTime = updateDrift();
    }

    long tickDifference = Math.max(0, currentNanoTime - startNanos);

    return TimeUnit.MILLISECONDS.toNanos(startMillis) + tickDifference;
  }

  private long updateDrift() {
    // This only happens once in a while, and is fast, so naive synchronization is okay
    synchronized (this) {
      long currentTimeMillis = System.currentTimeMillis();
      long currentNanoTime = System.nanoTime();
      if (currentNanoTime > startNanos + DRIFT_INTERVAL_NANOS) {
        startMillis = currentTimeMillis;
        startNanos = currentNanoTime;
      }

      return currentNanoTime;
    }
  }
}

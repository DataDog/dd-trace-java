package datadog.trace.core.util;

import java.util.concurrent.TimeUnit;

/** Rate limiter that only supports non-blocking retrieval of a single token */
public class SimpleRateLimiter {
  private final long capacity;
  private long tokens;
  private long lastRefillTime;
  private final long refillIntervalInNanos;
  private final TimeSource timeSource;

  public SimpleRateLimiter(long rate, TimeUnit unit) {
    this(rate, unit, new SystemNanoTimeSource());
  }

  protected SimpleRateLimiter(long rate, TimeUnit unit, TimeSource timeSource) {
    refillIntervalInNanos = unit.toNanos(1) / rate;
    capacity = rate;
    tokens = rate;
    this.timeSource = timeSource;
    lastRefillTime = timeSource.getTime();
  }

  public synchronized boolean tryAcquire() {
    fill();

    if (tokens > 0) {
      tokens--;
      return true;
    } else {
      return false;
    }
  }

  private void fill() {
    long timeElapsedSinceLastRefill = timeSource.getTime() - lastRefillTime;
    long intervals = timeElapsedSinceLastRefill / refillIntervalInNanos;
    tokens = Math.min(capacity, tokens + intervals);

    lastRefillTime += intervals * refillIntervalInNanos;
  }

  // This can probably be extracted to be more generic
  interface TimeSource {
    long getTime();
  }

  static class SystemNanoTimeSource implements TimeSource {
    @Override
    public long getTime() {
      return System.nanoTime();
    }
  }
}

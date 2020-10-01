package datadog.trace.core.util;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.api.utils.MathUtils;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter that only supports non-blocking retrieval of a single token at a minimum rate of 1
 * per second. Tokens are not smoothed across the second
 */
public class SimpleRateLimiter {
  private static final long REFILL_INTERVAL = TimeUnit.SECONDS.toNanos(1);
  private final long capacity;
  private final AtomicLong tokens;
  private final AtomicLong lastRefillTime;
  private final TimeSource timeSource;

  public SimpleRateLimiter(long rate) {
    this(rate, SystemTimeSource.INSTANCE);
  }

  protected SimpleRateLimiter(long rate, TimeSource timeSource) {
    this.timeSource = timeSource;

    capacity = Math.max(1, rate);

    tokens = new AtomicLong(capacity);

    lastRefillTime = new AtomicLong(timeSource.getNanoTime());
  }

  public boolean tryAcquire() {
    long now = timeSource.getNanoTime();
    long localRefill = lastRefillTime.get();
    long timeElapsedSinceLastRefill = now - localRefill;

    // Attempt to refill tokens if an interval has passed
    // Only refill the tokens if this thread wins a race
    if (timeElapsedSinceLastRefill > REFILL_INTERVAL) {
      if (lastRefillTime.compareAndSet(localRefill, now)) {
        tokens.set(capacity);
      }

      return tryAcquire();
    }

    return MathUtils.boundedDecrement(tokens, 0);
  }
}

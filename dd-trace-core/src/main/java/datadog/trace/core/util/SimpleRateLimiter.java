package datadog.trace.core.util;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter that only supports non-blocking retrieval of a single token at a minimum rate of 1
 * per second. Tokens are not smoothed across the second.
 */
public class SimpleRateLimiter {
  private final TimeSource timeSource;
  private final int capacity;
  private final long startNanos;
  private final AtomicLong secondsAndCount;

  public SimpleRateLimiter(int rate) {
    this(rate, SystemTimeSource.INSTANCE);
  }

  protected SimpleRateLimiter(int rate, TimeSource timeSource) {
    this.timeSource = timeSource;
    this.startNanos = timeSource.getNanoTicks();
    capacity = Math.max(1, rate);
    secondsAndCount = new AtomicLong(0);
  }

  public boolean tryAcquire() {
    long storedSecondsAndCount;
    long newSecondsAndCount;
    int seconds = 0;
    boolean readTime = true;
    do {
      storedSecondsAndCount = secondsAndCount.get();
      if (readTime) {
        // There will be an issue when the application has been running for more than 2^31 seconds,
        // roughly 68 years, so that is an acceptable trade off
        seconds = (int) TimeUnit.NANOSECONDS.toSeconds(timeSource.getNanoTicks() - startNanos);
        readTime = false;
      }
      final int storedSeconds = getStoredSeconds(storedSecondsAndCount);
      final int storedCount = getStoredCount(storedSecondsAndCount);
      final int diff = seconds - storedSeconds;
      if (diff <= 0) {
        // We're roughly in the same second, so try to acquire a token
        final int count = storedCount + 1;
        if (count > capacity || count < 0) {
          // We're all out of tokens
          return false;
        }
        newSecondsAndCount = combineSecondsAndCount(storedSeconds, count);
        if (diff < 0) {
          // If we fail to acquire a token, then reread the time since it's taken too long
          readTime = true;
        }
      } else {
        // At least one second has elapsed, so try to reset the tokens
        newSecondsAndCount = combineSecondsAndCount(seconds, 1);
      }
    } while (!secondsAndCount.compareAndSet(storedSecondsAndCount, newSecondsAndCount));

    return true;
  }

  public int getCapacity() {
    return capacity;
  }

  private static int getStoredSeconds(long timeAndCount) {
    return (int) (timeAndCount >> 32);
  }

  private static int getStoredCount(long timeAndCount) {
    return (int) (timeAndCount & Integer.MAX_VALUE);
  }

  private static long combineSecondsAndCount(int seconds, int count) {
    // We can safely assume that count is a positive int
    return ((long) (seconds & Integer.MAX_VALUE)) << 32 | count;
  }
}

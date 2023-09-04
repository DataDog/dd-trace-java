package com.datadog.appsec.gateway;

import datadog.trace.api.time.TimeSource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter that applies a limit of operations per second. It stores the count for the current
 * second and the count for the previous second. It checks that the total count, defined as the
 * number of counts for the current second plus (1 - elapsed fraction of current second) * previous
 * second count, does not exceed a set limit.
 *
 * <p>The state is stored in a single 64-bit number, so that it can be updated atomically in an
 * efficient manner. The least significant 20 bits are reserved to the current second count, the
 * next 20 bits to the previous second count, and the most significant 24-bits for the time of the
 * last update.
 *
 * <p>The fact that only 24-bits are used for the time, and therefore that the time wraps around
 * every 2^24 seconds (~194 days) results in that ew might erroneously think that the last update
 * was done in the current second, when in fact it was done 194 days before. This could be used by
 * an attacker to force throttling by performing their attacks in a 1-second span every 194 days.
 * However, for this to work, the server would have to get no other events by other users in this
 * same time span.
 */
public class RateLimiter {
  private static final long MASK_COUNT_CUR_SEC = 0xFFFFFL;
  private static final long MASK_COUNT_PREV_SEC = 0xFFFFF00000L;
  private static final int SHIFT_COUNT_PREV_SEC = 20;
  private static final int SHIFT_CUR_SEC = 40;
  private static final int MAX_LIMIT = 0xFFFFF; // 2^20 -1
  private static final int TIME_RING_MASK = 0xFFFFFF; // 24 bits

  private final TimeSource timeSource;
  private final ThrottledCallback throttledCb;

  public interface ThrottledCallback {
    void onThrottled();

    ThrottledCallback NOOP = () -> {};
  }

  private final int limitPerSec;
  private final AtomicLong state = new AtomicLong();

  public RateLimiter(int limitPerSec, TimeSource timeSource, ThrottledCallback cb) {
    this.limitPerSec = Math.min(Math.max(limitPerSec, 0), MAX_LIMIT);
    this.timeSource = timeSource;
    this.throttledCb = cb;
  }

  public boolean isThrottled() {
    long curSec = this.timeSource.getNanoTicks();
    long storedState;
    long newState;

    do {
      storedState = this.state.get();
      int storedCurCount = (int) (storedState & MASK_COUNT_CUR_SEC);
      int storedPrevCount = (int) ((storedState & MASK_COUNT_PREV_SEC) >> SHIFT_COUNT_PREV_SEC);
      int storedCurSec24 = (int) (storedState >>> SHIFT_CUR_SEC);

      int curSec24 = curSecond24bit(curSec);
      int diff = (curSec24 - storedCurSec24) & TIME_RING_MASK;
      while (true) {
        switch (diff) {
          case 0:
            {
              int count =
                  storedCurCount
                      + (int)
                          (storedPrevCount
                              * (1.0f - (float) (curSec % 1000000000L) / 1000000000.0f));
              if (count >= limitPerSec) {
                this.throttledCb.onThrottled();
                return true;
              }
              newState = storedState + 1;
              break;
            }
          case 1:
            {
              int count =
                  (int) (storedCurCount * (1.0f - (float) (curSec % 1000000000L) / 1000000000.0f));
              if (count >= limitPerSec) {
                // this is very unlikely to happen because the 2nd factor above must be 1
                // (we effectively round down when we cast to int)
                this.throttledCb.onThrottled();
                return true;
              }
              newState =
                  ((long) curSec24 << SHIFT_CUR_SEC)
                      | (((long) storedCurCount) << SHIFT_COUNT_PREV_SEC)
                      | 1L;
              break;
            }
          case 0xFFFFFF:
            {
              // we fell 1 second behind the current second (mod 0x1000000)
              curSec = this.timeSource.getNanoTicks();
              curSec24 = curSecond24bit(curSec);
              diff = (curSec24 - storedCurSec24) & TIME_RING_MASK;
              if (diff != 0xFFFFFF) {
                continue; // reevaluate switch
              }
              // else we're still behind, so we likely wrapped around since the last write
              // in that case, fall to default case
            }
          default:
            newState = ((long) curSec24 << SHIFT_CUR_SEC) | 1L;
        }
        break; // while (true)
      }
    } while (!state.compareAndSet(storedState, newState));
    return false;
  }

  private static int curSecond24bit(long nanoTime) {
    long secs = nanoTime / 1000000000L;
    return (int) (secs & TIME_RING_MASK);
  }
}

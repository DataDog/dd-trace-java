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
 * efficient manner. The least significant 24 bits are reserved to the current second count, the
 * next 24 bits to the previous second count, and the most significant 16-bits for the time of the
 * last update.
 *
 * <p>The fact that only 16-bits are used for the time, and therefore that the time wraps around
 * every 2^16 seconds (~18 hours) results in two limitations:
 *
 * <ol>
 *   <li>We might erroneously think that the last update was done in the current second, when in
 *       fact it was done 18 hours before. This could be used by an attacker to force throttling by
 *       performing their attacks in a 1-second span every 18 hours. However, for this to work, the
 *       server would have to get no other events by other users in this same time span.
 *   <li>We consider the current count to refer to current second both if the timestamp that we got,
 *       which we read only once, corresponds to the stored timestamp and if it is 1 second behind
 *       (rather than simply being either the stored timestamp or any number of seconds behind) in
 *       order to limit the amount of times we erroneously think the stored timestamp is current
 *       after a wrap around. If 1) isThrottled takes more than 1 second to run, and 2) the limit is
 *       not reached during this interval, it is possible that we erroneously reset the count.
 *       Taking more than 1 second to run is unlikely though, and in the scenario where this
 *       happens, there are likely many concurrent updates in other threads that cause multiple CAS
 *       failures and retries, therefore the limit is more likely to be reached in that scenario.
 * </ol>
 *
 * <p>If this is deemed a serious problem, it could be fixed by reserving more bits to the timestamp
 * (32 bits would mean we would get the same value every 136 years), at the cost of reducing the max
 * limit to 2^16 operations. Fewer bits can be used, but then subtracting the time of the
 * construction is probably advised.
 */
public class RateLimiter {
  private static final long MASK_COUNT_CUR_SEC = 0xFFFFFFL;
  private static final long MASK_COUNT_PREV_SEC = 0xFFFFFF000000L;
  private static final int SHIFT_COUNT_PREV_SEC = 24;
  private static final int SHIFT_CUR_SEC = 48;

  private final TimeSource timeSource;
  private final ThrottledCallback throttledCb;

  public interface ThrottledCallback {
    void onThrottled();

    ThrottledCallback NOOP = () -> {};
  }

  private final int limitPerSec;
  private final AtomicLong state = new AtomicLong();

  public RateLimiter(int limitPerSec, TimeSource timeSource, ThrottledCallback cb) {
    this.limitPerSec = limitPerSec;
    this.timeSource = timeSource;
    this.throttledCb = cb;
  }

  public final boolean isThrottled() {
    long curSec = this.timeSource.getNanoTime();
    long storedState;
    long newState;

    do {
      storedState = this.state.get();
      int storedCurCount = (int) (storedState & MASK_COUNT_CUR_SEC);
      int storedPrevCount = (int) ((storedState & MASK_COUNT_PREV_SEC) >> SHIFT_COUNT_PREV_SEC);
      int storedCurSec16 = (int) (storedState >>> SHIFT_CUR_SEC);

      int curSec16 = curSecond16bit(curSec);
      int diff = (curSec16 - storedCurSec16) & 0xFFFF;
      if (diff == 0 || diff == 0xFFFF /* -1 in ring Z_{0x10000} */) {
        int count =
            storedCurCount
                + (int) (storedPrevCount * (1.0f - (float) (curSec % 1000000000L) / 1000000000.0f));
        if (count >= limitPerSec) {
          this.throttledCb.onThrottled();
          return true;
        }
        newState = storedState + 1;
      } else if (diff == 1) {
        int count =
            (int) (storedCurCount * (1.0f - (float) (curSec % 1000000000L) / 1000000000.0f));
        if (count >= limitPerSec) {
          this.throttledCb.onThrottled();
          return true;
        }
        newState =
            ((long) curSec16 << SHIFT_CUR_SEC)
                | (((long) storedCurCount) << SHIFT_COUNT_PREV_SEC)
                | 1L;
      } else {
        newState = ((long) curSec16 << SHIFT_CUR_SEC) | 1L;
      }
    } while (!state.compareAndSet(storedState, newState));
    return false;
  }

  private static int curSecond16bit(long nanoTime) {
    long secs = nanoTime / 1000000000L;
    return (int) (secs & 0xFFFFL);
  }
}

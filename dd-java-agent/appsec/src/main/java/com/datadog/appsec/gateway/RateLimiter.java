package com.datadog.appsec.gateway;

import java.util.concurrent.atomic.AtomicLong;

public class RateLimiter {

  private final ThrottledCallback throttledCb;

  public interface ThrottledCallback {
    void onThrottled();

    ThrottledCallback NOOP = () -> {};
  }

  private final int limitPerSec;
  private final AtomicLong state = new AtomicLong();

  public RateLimiter(int limitPerSec, ThrottledCallback cb) {
    this.limitPerSec = limitPerSec;
    this.throttledCb = cb;
  }

  public final boolean isThrottled() {
    long curSec = getNanoTime();
    long storedState;
    long newState;

    do {
      storedState = this.state.get();
      int storedCurCount = (int) (storedState & 0xFFFFFF);
      int storedPrevCount = (int) ((storedState & 0xFFFFFF000000L) >> 24);
      int storedCurSec16 = (int) (storedState >>> 48);

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
        newState = (storedState & ~0xFFFFFFL) | (storedCurCount + 1);
      } else if (diff == 1) {
        int count =
            (int) (storedCurCount * (1.0f - (float) (curSec % 1000000000L) / 1000000000.0f));
        if (count >= limitPerSec) {
          this.throttledCb.onThrottled();
          return true;
        }
        newState = ((long) curSec16 << 48) | (((long) storedCurCount) << 24) | 1L;
      } else {
        newState = ((long) curSec16 << 48) | 1L;
      }
    } while (!state.compareAndSet(storedState, newState));
    return false;
  }

  protected long getNanoTime() {
    return System.nanoTime();
  }

  private static int curSecond16bit(long nanoTime) {
    long secs = nanoTime / 1000000000L;
    return (int) (secs & 0xFFFFL);
  }
}

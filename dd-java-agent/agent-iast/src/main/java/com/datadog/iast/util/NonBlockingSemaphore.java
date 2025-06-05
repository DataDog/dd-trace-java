package com.datadog.iast.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public interface NonBlockingSemaphore {

  default boolean acquire() {
    return acquire(1);
  }

  boolean acquire(int count);

  default int release() {
    return release(1);
  }

  int release(int count);

  int available();

  void reset();

  static NonBlockingSemaphore unlimited() {
    return new UnlimitedSemaphore();
  }

  static NonBlockingSemaphore withPermitCount(final int permits) {
    assert permits > 0;
    return permits == 1 ? new AtomicBooleanSemaphore() : new AtomicIntegerSemaphore(permits);
  }

  class UnlimitedSemaphore implements NonBlockingSemaphore {

    @Override
    public boolean acquire(final int count) {
      return true;
    }

    @Override
    public int release(final int count) {
      return Integer.MAX_VALUE;
    }

    @Override
    public int available() {
      return Integer.MAX_VALUE;
    }

    @Override
    public void reset() {}
  }

  class AtomicBooleanSemaphore implements NonBlockingSemaphore {
    private final AtomicBoolean available = new AtomicBoolean();

    public AtomicBooleanSemaphore() {
      reset();
    }

    @Override
    public boolean acquire(final int count) {
      return count == 1 && available.compareAndSet(true, false);
    }

    @Override
    public int release(final int count) {
      reset();
      return 1;
    }

    @Override
    public final void reset() {
      this.available.set(true);
    }

    @Override
    public int available() {
      return this.available.get() ? 1 : 0;
    }
  }

  class AtomicIntegerSemaphore implements NonBlockingSemaphore {

    private final int permits;

    private final AtomicInteger available = new AtomicInteger();

    public AtomicIntegerSemaphore(final int permits) {
      this.permits = permits;
      reset();
    }

    @Override
    public boolean acquire(final int count) {
      if (available.get() == 0) {
        return false;
      }
      return available.getAndUpdate(x -> x >= count ? x - count : x) >= count;
    }

    @Override
    public int release(final int count) {
      return available.updateAndGet(x -> (x + count) <= permits ? x + count : x);
    }

    @Override
    public final void reset() {
      this.available.set(permits);
    }

    @Override
    public int available() {
      return this.available.get();
    }
  }
}

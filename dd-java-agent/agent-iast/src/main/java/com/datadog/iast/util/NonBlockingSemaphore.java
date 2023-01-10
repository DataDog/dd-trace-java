package com.datadog.iast.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public interface NonBlockingSemaphore {

  boolean acquire();

  int release();

  int available();

  void reset();

  static NonBlockingSemaphore withPermitCount(final int permits) {
    assert permits > 0;
    return permits == 1 ? new AtomicBooleanSemaphore() : new AtomicIntegerSemaphore(permits);
  }

  class AtomicBooleanSemaphore implements NonBlockingSemaphore {
    private final AtomicBoolean available = new AtomicBoolean();

    public AtomicBooleanSemaphore() {
      reset();
    }

    @Override
    public boolean acquire() {
      return available.compareAndSet(true, false);
    }

    @Override
    public int release() {
      reset();
      return available();
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
    public boolean acquire() {
      if (available.get() == 0) {
        return false;
      }
      return available.getAndUpdate(x -> x > 0 ? x - 1 : x) > 0;
    }

    @Override
    public int release() {
      return available.updateAndGet(x -> x < permits ? x + 1 : x);
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

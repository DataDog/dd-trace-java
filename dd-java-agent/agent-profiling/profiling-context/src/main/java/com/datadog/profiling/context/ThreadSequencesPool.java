package com.datadog.profiling.context;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ThreadSequencesPool {
  private static final Logger log = LoggerFactory.getLogger(ThreadSequencesPool.class);

  private final ThreadSequences[] instances;
  private volatile int counter = 0;
  private static final AtomicIntegerFieldUpdater<ThreadSequencesPool> COUNTER_UPDATER = AtomicIntegerFieldUpdater.newUpdater(ThreadSequencesPool.class, "counter");

  private final int maxSize;

  ThreadSequencesPool() {
    this(128, 512000);
  }

  ThreadSequencesPool(int initialSize, int maxSize) {
    this.maxSize = maxSize;
    instances = new ThreadSequences[maxSize];
    for (int i = 0; i < initialSize; i++) {
      instances[i] = new ThreadSequences(this, i);
    }
  }

  ThreadSequences claim() {
    int cntr = 0;
    long ts = System.nanoTime();
    int ptr = -1;
    while (((ptr = COUNTER_UPDATER.getAndUpdate(this, p -> (p & 0x80000000) == 0 ? Math.min(p + 1, maxSize) : p)) & 0x80000000) != 0) {
      Thread.yield();
      if ((cntr = (++cntr % 1000)) == 0) {
        if (System.nanoTime() - ts > 10_000_000L) {
          // max 10ms wait
          log.warn("ThreadSequences pool is contended (max size={})", maxSize);
          return null;
        }
        LockSupport.parkNanos(10_000L);
      }
    }
    if (ptr >= maxSize) {
      return null;
    }
    ThreadSequences sequences = instances[ptr];
    if (sequences == null) {
      sequences = new ThreadSequences(this, ptr);
      instances[ptr] = sequences;
    }
    return sequences;
  }

  void release(int ptr) {
    int target = COUNTER_UPDATER.updateAndGet(this, p -> ((p & 0x7fffffff) - 1) | 0x80000000);
    ThreadSequences ts = instances[target & 0x7fffffff];
    instances[ptr] = ts;
    ts.ptr = ptr;
    COUNTER_UPDATER.updateAndGet(this, p -> p == target ? (p & 0x7fffffff) : p);
  }
}

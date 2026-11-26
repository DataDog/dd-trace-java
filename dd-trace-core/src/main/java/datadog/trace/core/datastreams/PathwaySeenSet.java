package datadog.trace.core.datastreams;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-size, lock-free set of pathway aggregation hashes, used by Data Streams lite mode to report
 * each unique pathway once per epoch.
 *
 * <p>When a key finds neither itself nor a free slot within {@link #MAX_PROBES} probes, the insert
 * is dropped and counted as an overflow. {@link #contains} keeps returning false for that key, so
 * the pathway is reported again rather than lost.
 */
final class PathwaySeenSet {
  static final int CAPACITY_BITS = 13;
  static final int CAPACITY = 1 << CAPACITY_BITS;
  static final int MAX_PROBES = 16;
  private static final int MASK = CAPACITY - 1;
  private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;

  private final AtomicLongArray slots = new AtomicLongArray(CAPACITY);
  private final LongAdder overflows = new LongAdder();

  boolean contains(long hash) {
    long key = key(hash);
    int index = index(key);
    for (int probe = 0; probe < MAX_PROBES; probe++) {
      long slot = slots.get((index + probe) & MASK);
      if (slot == key) {
        return true;
      }
      if (slot == 0) {
        return false;
      }
    }
    return false;
  }

  void insert(long hash) {
    long key = key(hash);
    int index = index(key);
    for (int probe = 0; probe < MAX_PROBES; probe++) {
      int i = (index + probe) & MASK;
      long slot = slots.get(i);
      if (slot == key) {
        return;
      }
      if (slot == 0) {
        if (slots.compareAndSet(i, 0, key) || slots.get(i) == key) {
          return;
        }
      }
    }
    overflows.increment();
  }

  void clear() {
    for (int i = 0; i < CAPACITY; i++) {
      slots.set(i, 0);
    }
  }

  /** Returns the number of overflowed inserts since the previous call. */
  long drainOverflows() {
    return overflows.sumThenReset();
  }

  // 0 marks a free slot
  private static long key(long hash) {
    return hash == 0 ? 1 : hash;
  }

  private static int index(long key) {
    return (int) ((key * GOLDEN_RATIO) >>> (64 - CAPACITY_BITS));
  }
}

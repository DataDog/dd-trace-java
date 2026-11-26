package datadog.trace.core.datastreams;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Caps how many messages per second start a pathway in this process, for Data Streams lite.
 *
 * <p>One budget is shared by every edge: a one-second window ({@code second << 32 | count}) in a
 * single {@link AtomicLong}. The first message on an edge this sampler hasn't seen is sampled even
 * when the budget is spent, so a new pathway shows up at once. Edges are remembered in a lock-free
 * open-addressing set; once it is full, unseen edges only get the shared budget. Nothing here
 * allocates after construction.
 */
final class OriginSampler {
  private static final int EDGE_CAPACITY = 16384; // power of two
  private static final int MAX_PROBES = 16;
  // 0 marks an empty slot
  private static final long EMPTY = 0;

  private final AtomicLongArray edges = new AtomicLongArray(EDGE_CAPACITY);
  private final AtomicLong window = new AtomicLong();
  private final int capPerSecond;
  private final long baseNanoTicks;

  OriginSampler(int capPerSecond, long nanoTicks) {
    this.capPerSecond = capPerSecond;
    // Seconds are counted from one second before creation so the window is never 0
    this.baseNanoTicks = nanoTicks - TimeUnit.SECONDS.toNanos(1);
  }

  /** Whether a message starting a pathway on the edge with this node hash is recorded. */
  boolean sample(long edgeHash, long nanoTicks) {
    return firstOnEdge(edgeHash == EMPTY ? 1 : edgeHash) || take(nanoTicks);
  }

  private boolean firstOnEdge(long key) {
    int index = mix(key);
    for (int probe = 0; probe < MAX_PROBES; probe++) {
      int slot = (index + probe) & (EDGE_CAPACITY - 1);
      long current = edges.get(slot);
      if (current == key) {
        return false;
      }
      if (current == EMPTY) {
        if (edges.compareAndSet(slot, EMPTY, key)) {
          return true;
        }
        if (edges.get(slot) == key) {
          return false;
        }
      }
    }
    return false;
  }

  private boolean take(long nanoTicks) {
    long second = TimeUnit.NANOSECONDS.toSeconds(nanoTicks - baseNanoTicks);
    while (true) {
      long current = window.get();
      if (second > current >>> 32) {
        if (window.compareAndSet(current, (second << 32) | 1)) {
          return true;
        }
      } else if ((current & 0xFFFFFFFFL) >= capPerSecond) {
        return false;
      } else if (window.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  private static int mix(long key) {
    long h = key * 0x9E3779B97F4A7C15L;
    return (int) (h ^ (h >>> 32));
  }
}

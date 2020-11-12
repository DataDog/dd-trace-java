package datadog.trace.common.metrics;

import java.util.Arrays;

/**
 * This is a thread-safe container for partial conflating and accumulating partial aggregates on the
 * same key.
 *
 * <p>Updates to an already consumed batch are rejected.
 *
 * <p>A batch can currently take at most 64 values. Attempts to add the 65th update will be
 * rejected.
 */
public final class Batch {

  public static final Batch NULL = new Batch(null);

  private volatile MetricKey key;

  private int count = 0;
  private long errorMask = 0L;
  private final long[] durations;

  Batch() {
    this(new long[64]);
  }

  private Batch(long[] durations) {
    this.durations = durations;
  }

  public MetricKey getKey() {
    return key;
  }

  public Batch withKey(MetricKey key) {
    this.key = key;
    return this;
  }

  public boolean add(boolean error, long durationNanos) {
    if (null != key && count < 64) {
      synchronized (this) {
        if (null != key) {
          return addExclusive(error, durationNanos);
        }
      }
    }
    return false;
  }

  boolean addExclusive(boolean error, long durationNanos) {
    if (count < 64) {
      if (error) {
        errorMask |= (1L << count);
      }
      durations[count++] = durationNanos;
      return true;
    }
    return false;
  }

  public synchronized void contributeTo(AggregateMetric aggregate) {
    this.key = null;
    aggregate
        .addErrors(Long.bitCount(errorMask))
        .addHits(count)
        .recordDurations(errorMask, durations);
    clear();
  }

  private void clear() {
    this.count = 0;
    this.errorMask = 0L;
    Arrays.fill(durations, 0L);
  }
}

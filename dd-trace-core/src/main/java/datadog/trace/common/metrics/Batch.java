package datadog.trace.common.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

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

  private static final int MAX_BATCH_SIZE = 64;
  public static final Batch NULL = new Batch((AtomicLongArray) null);

  /**
   * This counter has two states 1 - negative - the batch has been used, must not add values 2 -
   * otherwise - the number of values added to the batch
   */
  private final AtomicInteger count = new AtomicInteger(0);
  /** incremented when a duration has been added. */
  private final AtomicInteger committed = new AtomicInteger(0);

  private MetricKey key;
  private final AtomicLongArray durations;

  Batch(MetricKey key) {
    this(new AtomicLongArray(MAX_BATCH_SIZE));
    this.key = key;
  }

  Batch() {
    this(new AtomicLongArray(MAX_BATCH_SIZE));
  }

  private Batch(AtomicLongArray durations) {
    this.durations = durations;
  }

  public MetricKey getKey() {
    return key;
  }

  public Batch reset(MetricKey key) {
    this.key = key;
    this.count.set(0);
    return this;
  }

  public boolean isUsed() {
    return this.count.get() < 0;
  }

  public boolean add(long tag, long durationNanos) {
    // technically this would be wrong if there were 2^31 unsuccessful
    // attempts to add a value, but this an acceptable risk
    int position = count.getAndIncrement();
    if (position >= 0 && position < durations.length()) {
      durations.set(position, tag | durationNanos);
      committed.incrementAndGet();
      return true;
    }
    return false;
  }

  public void contributeTo(AggregateMetric aggregate) {
    int count = Math.min(this.count.getAndSet(Integer.MIN_VALUE), MAX_BATCH_SIZE);
    if (count >= 0) {
      // wait for the duration to have been set.
      // note this mechanism only supports a single reader
      while (committed.get() != count) ;
      committed.set(0);
      aggregate.recordDurations(count, durations);
    }
  }
}

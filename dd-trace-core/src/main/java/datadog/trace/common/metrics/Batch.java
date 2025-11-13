package datadog.trace.common.metrics;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
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
public final class Batch implements InboxItem {

  private static final int MAX_BATCH_SIZE = 64;
  private static final AtomicIntegerFieldUpdater<Batch> COUNT =
      AtomicIntegerFieldUpdater.newUpdater(Batch.class, "count");
  private static final AtomicIntegerFieldUpdater<Batch> COMMITTED =
      AtomicIntegerFieldUpdater.newUpdater(Batch.class, "committed");

  /**
   * This counter has two states:
   *
   * <ol>
   *   <li>negative: the batch has been used, must not add values
   *   <li>otherwise: the number of values added to the batch
   * </ol>
   */
  private volatile int count = 0;

  /** incremented when a duration has been added. */
  private volatile int committed = 0;

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
    COUNT.lazySet(this, 0);
    return this;
  }

  public boolean isUsed() {
    return count < 0;
  }

  public boolean add(long tag, long durationNanos) {
    // technically this would be wrong if there were 2^31 unsuccessful
    // attempts to add a value, but this an acceptable risk
    int position = COUNT.getAndIncrement(this);
    if (position >= 0 && position < durations.length()) {
      durations.set(position, tag | durationNanos);
      COMMITTED.getAndIncrement(this);
      return true;
    }
    return false;
  }

  public void contributeTo(AggregateMetric aggregate) {
    int count = Math.min(COUNT.getAndSet(this, Integer.MIN_VALUE), MAX_BATCH_SIZE);
    if (count >= 0) {
      // wait for the duration to have been set.
      // note this mechanism only supports a single reader
      while (committed != count) {
        Thread.yield();
      }
      COMMITTED.lazySet(this, 0);
      aggregate.recordDurations(count, durations);
    }
  }
}

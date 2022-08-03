package com.datadog.profiling.context;

import datadog.trace.api.function.Consumer;
import datadog.trace.util.AgentTaskScheduler;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.jctools.queues.MpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A size bound expiration tracker.<br>
 * <br>
 *
 * <p>The main idea is to keep the tracked items in a number of buckets corresponding to the
 * expiration period and granularity. In short, there will be 'period / granularity' buckets which
 * will be inspected in round-robin fashion and the oldest bucket will be marked for expiration when
 * an element is first time added to a new bucket. This can be simplified by expiring bucket
 * {@literal I+1} when an item is added to bucket {@literal I} first time after it was
 * expired/cleaned. The {@literal I+1} index is a subject to wrapping, such that when we have
 * {@literal N} buckets and {@literal I==N} the index of the bucket to expire will be {@literal 0}.
 *
 * <p>In order to reduce contention each bucket is further 'striped' based on the 'parallelism'
 * setting and each stripe is guarded by its own lock. The number of stripes is estimated in such a
 * way that even if all 'parallelism' threads are accessing the stripe concurrently there is ~50%
 * chance of uncontended access.
 *
 * <p>Currently, the cleanup of expired buckets is handled by a dedicated thread to reduce the
 * latency effect on the traced code. However, it would be possible to handle the cleanup inline -
 * that would reduce overall additional CPU consumption for the price of increased latency of the
 * traced code.
 *
 * <p>The tracker is size-bound - it has defined capacity and it will track at most that number of
 * expirables. If, for whatever reason the tracker can not create a new expirable it will return
 * {@linkplain Expirable#EMPTY} instance and the caller can then decide how to handle the situation.
 */
final class ExpirationTracker implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ExpirationTracker.class);

  /** An expirable element with optional code to run upon expiration. */
  static final class Expirable {
    static final Expirable EMPTY = new Expirable(-1, -1);

    private volatile Consumer<Expirable> onExpired;

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<Expirable, Consumer> ON_EXPIRED_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(Expirable.class, Consumer.class, "onExpired");

    private final long expiration;
    private volatile long nanos;
    private static final AtomicLongFieldUpdater<Expirable> NANOS_UPDATER =
        AtomicLongFieldUpdater.newUpdater(Expirable.class, "nanos");

    private final Bucket bucket;
    private volatile int pos;

    private Expirable(long ts, long expiration) {
      this(null, -1, ts, expiration, null);
    }

    Expirable(Bucket bucket, int pos, long nanos, long expiration, Consumer<Expirable> onExpired) {
      if (expiration < 0 && onExpired != null) {
        throw new IllegalArgumentException("Expiration must be positive number");
      }
      this.bucket = bucket;
      this.pos = pos;
      this.onExpired = onExpired;
      this.nanos = nanos;
      this.expiration = expiration;
    }

    /**
     * Set the on-expiration callback
     *
     * @param callback the callback
     */
    void setOnExpiredCallback(Consumer<Expirable> callback) {
      this.onExpired = callback;
    }

    /**
     * 'Touch' the expirable - reset the timestamp since when the expiration is calculated to the
     * given timestamp
     *
     * @param nanos the timestamp in nanoseconds
     */
    void touch(long nanos) {
      NANOS_UPDATER.set(this, nanos);
    }

    /**
     * Expire the item. Remove the item from the tracker and call the on-expiration code, if
     * present.
     */
    @SuppressWarnings("unchecked")
    void expire() {
      Consumer<Expirable> callback = ON_EXPIRED_UPDATER.getAndSet(this, null);
      if (hasExpiration() && callback != null) {
        synchronized (bucket) {
          bucket.remove(pos);
        }
        try {
          callback.accept(this);
        } catch (Throwable t) {
          log.debug("Unexpected exception while executing expiration callback", t);
        }
      }
    }

    /**
     * Check whether the expirable is already expired - the expiration period has run out and the
     * expirable was properly cleaned.
     *
     * @return {@literal true} if the expirable was expired
     */
    boolean isExpired() {
      return hasExpiration() && onExpired == null;
    }

    /**
     * Check whether the expirable is about to expire in the given timestamp
     *
     * @param nowNs timestamp in nanoseconds
     * @return {@literal true} if the expirable is eligible for expiration in the given timestamp
     */
    boolean isExpiring(long nowNs) {
      return onExpired != null && hasExpiration() && (nowNs - nanos) >= expiration;
    }

    /**
     * Check whether the expirable has the expiration set
     *
     * @return {@literal true} if the expiration period > 0
     */
    boolean hasExpiration() {
      return expiration > 0;
    }
  }

  /**
   * A single expiration tracker bucket.<br>
   * Consists of a number {@linkplain Expirable} instances stored in a fixed size array.
   */
  static final class Bucket {
    /**
     * An observability callback to get notified once the bucket gets filled up or when there is
     * again some capacity available.
     */
    interface Callback {
      Callback EMPTY =
          new Callback() {
            @Override
            public void onBucketFull() {}

            @Override
            public void onBucketAvailable() {}
          };

      void onBucketFull();

      void onBucketAvailable();
    }

    private int size = 0;
    private final Expirable[] data;
    private final Callback fillupCallback;

    private volatile boolean fillupTriggered = false;

    Bucket(int capacity, Callback fillupCallback) {
      data = new Expirable[capacity];
      this.fillupCallback = fillupCallback == null ? Callback.EMPTY : fillupCallback;
    }

    /**
     * Create and add a new {@linkplain Expirable} instance if possible withoug violating capacity
     * constraints.
     *
     * @param nanos timestamp in nanoseconds
     * @param expiration the expiration period in nanoseconds
     * @param onExpired the expiration callback
     * @return a new instance or {@linkplain Expirable#EMPTY} if the instance can not be added
     */
    Expirable add(long nanos, long expiration, Consumer<Expirable> onExpired) {
      if (size == data.length) {
        return Expirable.EMPTY;
      }
      fireBucketFull();
      int pos = size;
      Expirable e = new Expirable(this, pos, nanos, expiration, onExpired);
      data[size++] = e;
      return e;
    }

    /**
     * Remove the {@linkplain Expirable} instance from the given position in the array
     *
     * @param pos the position of the element to remove
     */
    private void remove(int pos) {
      if (pos > -1) {
        data[pos] = data[--size];
        data[pos].pos = pos;
        data[size] = null;

        fireBucketAvailable();
      }
    }

    /**
     * Try and remove all {@linkplain Expirable} instances from the tracking array.<br>
     * <br>
     * Due to the support of 'tuch' capability when the expiration can be continuously moved forward
     * it may happen that certain {@link Expirable expirables} are still in a bucket which really
     * does not correspond to their expiration. The problem is that shuffling around the {@link
     * Expirable expirables} between buckets each time they are 'touched' would be extremely costly
     * as both buckets would have to be locked and the capacity problems would have to be resolved
     * (eg. the target bucket might already be full etc.). Therefore, it is easier to keep the
     * non-expiring {@link Expirable expirables} in the bucket, mark the bucket as tainted and
     * re-schedule cleanup with the hopes that the elements will expire eventually.
     *
     * @param nanos timestamp in nanoseconds
     * @return {@literal true} if the bucket was fully cleaned
     */
    boolean clean(long nanos) {
      int reinsertionIdx = 0;
      for (int i = 0; i < data.length; i++) {
        if (data[i] == null) {
          // the data is auto-compacted so first 'null' element means all the followup ones are null
          // also
          break;
        }
        Expirable e = data[i];
        if (e.isExpiring(nanos)) {
          try {
            // call the 'expiration' callback
            if (e.onExpired != null) {
              e.onExpired.accept(e);
            }
          } catch (Throwable t) {
            log.debug("Unexpected exception while executing expiration callback", t);
          } finally {
            // remove the reference to the expirable element
            data[i] = null;
          }
        } else {
          // this element is not eligible for expiration yet
          if (reinsertionIdx != i) {
            // Move the element to the beginning of the tracking array -
            // this will naturally compact the remaining elements and help keeping the invariant
            // that the first discovered 'null' reference should break the cleanup run as the
            // remnant of the tracking array would be empty
            data[reinsertionIdx] = data[i];
            data[reinsertionIdx].pos = i;
            // clean up the original slot
            data[i] = null;
          }
          // if reinsertionIdx == i the data stay in its place so data[i] is not be nulled
          reinsertionIdx++;
        }
      }
      // update the number of tracked elements
      size = reinsertionIdx;
      fireBucketAvailable();
      // set the tainted status if there were any reinsertions
      int tainted = reinsertionIdx;
      return tainted == 0;
    }

    private void fireBucketAvailable() {
      if (fillupTriggered && size < data.length) {
        try {
          fillupCallback.onBucketAvailable();
        } catch (Throwable t) {
          log.debug("Unexpected exception while executing bucket callback", t);
        } finally {
          fillupTriggered = false;
        }
      }
    }

    private void fireBucketFull() {
      if (size == data.length - 1) {
        if (!fillupTriggered) {
          try {
            fillupCallback.onBucketFull();
          } catch (Throwable t) {
            log.debug("Unexpected exception while executing bucket callback", t);
          } finally {
            fillupTriggered = true;
          }
        }
      }
    }
  }

  private static final class BucketAvailabilityTracker implements Bucket.Callback {
    private final AtomicInteger availableBuckets;
    private final int buckets;

    BucketAvailabilityTracker(int buckets) {
      this.buckets = buckets;
      availableBuckets = new AtomicInteger(buckets);
    }

    boolean hasAvailableBuckets() {
      return availableBuckets.get() > 0;
    }

    @Override
    public void onBucketFull() {
      availableBuckets.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    @Override
    public void onBucketAvailable() {
      availableBuckets.updateAndGet(v -> v < buckets ? v + 1 : buckets);
    }
  }

  /** Striped-locking implementation over buckets. */
  @SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter"})
  static final class StripedBucket {
    // prime-number increments
    private static final int[] INCREMENTS = new int[] {5, 7, 11, 13, 19, 23, 29, 31, 37, 41, 47};
    private final Bucket[] buckets;
    private final Lock[] locks;

    private final int numStripes;

    private final BucketAvailabilityTracker availabilityTracker;

    private long lastCleanupTs = -1;

    private volatile int bulkState = 0;
    private static final AtomicIntegerFieldUpdater<StripedBucket> BULK_STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(StripedBucket.class, "bulkState");

    StripedBucket(int parallelism, int capacity) {
      this.numStripes = parallelism * 2;
      buckets = new Bucket[numStripes];
      locks = new Lock[numStripes];
      int bucketCapacity = (capacity / numStripes) + ((capacity % numStripes) == 0 ? 0 : 1);
      availabilityTracker = new BucketAvailabilityTracker(numStripes);
      for (int i = 0; i < numStripes; i++) {
        buckets[i] = new Bucket(bucketCapacity, availabilityTracker);
        locks[i] = new ReentrantLock();
      }
    }

    /**
     * Add a new {@linkplain Expirable} instance if possible without violating capacity constraints.
     *
     * @param nanos timestamp in nanoseconds
     * @param expiration expiration period in nanoseconds
     * @param onExpired expiration callback
     * @return a new {@linkplain Expirable} instance or {@linkplain Expirable#EMPTY}
     */
    Expirable add(long nanos, long expiration, Runnable onExpired) {
      // Short-circuit a possibly full striped bucket.
      // This will not prevent multiple threads filling up the bucket
      // concurrently but will prevent useless attempts to find an
      // available stripe if the bucket is completely full.
      if (!availabilityTracker.hasAvailableBuckets()) {
        return Expirable.EMPTY;
      }

      // Randomize the starting point for accessing the stripes
      ThreadLocalRandom rnd = ThreadLocalRandom.current();
      int stripe = rnd.nextInt(numStripes);
      int increment = INCREMENTS[rnd.nextInt(INCREMENTS.length)];

      while (BULK_STATE_UPDATER.getAndUpdate(this, s -> s > -1 ? s + 1 : -1) == -1) {
        Thread.yield();
      }
      try {
        // Iterate over the stripes at most once to find a stripe which is able to hold
        // one more item.
        for (int i = 0; i < numStripes; i++) {
          Bucket bucket = buckets[stripe];
          while (!locks[stripe].tryLock()) {
            Thread.yield();
          }
          try {
//        synchronized (bucket) {
            Expirable e = bucket.add(nanos, expiration, t -> onExpired.run());
            if (e != Expirable.EMPTY) {
              return e;
            }
          } finally {
            locks[stripe].unlock();
          }
          // use a prime number increment to iterate over the stripes in a pseudo-random manner
          stripe = (stripe + increment) % numStripes;
//        }
        }
      } finally {
        BULK_STATE_UPDATER.getAndUpdate(this, s -> s > -1 ? s - 1 : -1);
      }
      return Expirable.EMPTY;
    }

    /**
     * Cleans up the bucket by expiring all eligible elements.<br>
     * This method should always be called from the same thread and from only one thread at a time -
     * otherwise the performance may suffer because the same bucket could be cleaned up several
     * times
     *
     * @param nanos timestamp in nanoseconds
     * @return {@literal true} if the bucket was completely cleaned
     */
    boolean clean(long nanos) {
      if (lastCleanupTs == nanos) {
        return true; // already processed in this epoch
      }
      boolean cleaned = true;
      while (BULK_STATE_UPDATER.getAndUpdate(this, s -> s == 0 ? -1 : s) != 0) {
        Thread.yield();
      }
      try {
        for (int i = 0; i < numStripes; i++) {
          Bucket bucket = buckets[i];
          cleaned = bucket.clean(nanos) && cleaned;
        }
      } finally {
        BULK_STATE_UPDATER.compareAndSet(this, -1, 0);
      }
      lastCleanupTs = nanos;
      return cleaned;
    }
  }

  @FunctionalInterface
  interface NanoTimeSource {
    long getNanos();
  }

  private final int capacity;
  private final int bucketCapacity;
  private final StripedBucket[] buckets;
  private final int numBuckets;
  private final long expirationNs;
  private final long granularityNs;

  private static final AtomicLongFieldUpdater<ExpirationTracker> LAST_ACCESSED_SLOT_UPDATER =
      AtomicLongFieldUpdater.newUpdater(ExpirationTracker.class, "lastAccessedSlot");
  private volatile long lastAccessedSlot = -1;
  private volatile boolean closed = false;

  private final MpscArrayQueue<StripedBucket> cleanupQueue;
  private final AgentTaskScheduler.Scheduled<ExpirationTracker> scheduledTask;
  private final NanoTimeSource timeSource;

  ExpirationTracker(
      long expiration, long granularity, TimeUnit timeUnit, int parallelism, int capacity) {
    this(expiration, granularity, timeUnit, parallelism, capacity, System::nanoTime, true);
  }

  ExpirationTracker(
      long expiration,
      long granularity,
      TimeUnit timeUnit,
      int parallelism,
      int capacity,
      NanoTimeSource timeSource,
      boolean scheduleCleanup) {
    if (expiration > 0 && expiration <= granularity) {
      throw new IllegalArgumentException("'expiration' must be larger than 'granularity'");
    }

    this.expirationNs = timeUnit.toNanos(expiration);
    this.granularityNs = timeUnit.toNanos(granularity);
    this.timeSource = timeSource;

    numBuckets = (int) (expiration / granularity) + 1;
    buckets = new StripedBucket[numBuckets];
    bucketCapacity = (capacity / numBuckets) + ((capacity % numBuckets) == 0 ? 0 : 1);
    log.debug("capacity: {}, num_buckets: {}, bucket_capacity: {}", capacity, numBuckets, bucketCapacity);

    this.capacity = numBuckets * bucketCapacity;
    for (int i = 0; i < numBuckets; i++) {
      buckets[i] = new StripedBucket(parallelism, bucketCapacity);
    }
    cleanupQueue = new MpscArrayQueue<>(numBuckets * 2);
    if (scheduleCleanup) {
      int schedulePeriodNs = Math.max((int) (granularityNs / 2d), 1);
      scheduledTask =
          AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
              ExpirationTracker::processCleanup,
              this,
              schedulePeriodNs,
              schedulePeriodNs,
              TimeUnit.NANOSECONDS);
    } else {
      scheduledTask = null;
    }
  }

  final List<StripedBucket> survivors = new ArrayList<>();
  private long lastCleanupSlot = -1;

  void processCleanup() {
    long nanos = timeSource.getNanos();
    long slot = getTimeSlot(nanos);
    try {
      // do not trigger cleanup for the same time slot
      if (slot > lastCleanupSlot) {
        // first process the expired queue
        int processed =
            cleanupQueue.drain(
                b -> {
                  if (!b.clean(nanos)) {
                    // if a bucket was not fully cleaned add it to survivors
                    survivors.add(b);
                  }
                });
        // re-add the surviving buckets
        cleanupQueue.addAll(survivors);

        if (processed == 0) {
          // if not scheduled externally expire the bucket related to the current timestamp
          int toCleanIdx = getBucketIndex(slot);
          StripedBucket toClean = buckets[toCleanIdx];
          if (!toClean.clean(nanos)) {
            // re-add the surviving bucket
            cleanupQueue.add(toClean);
          }
        }
      }
    } finally {
      lastCleanupSlot = slot;
      survivors.clear();
    }
  }

  /**
   * Track new {@linkplain Expirable}
   *
   * @return a new {@linkplain Expirable} instance or {@linkplain Expirable#EMPTY}
   */
  Expirable track() {
    return track(() -> {});
  }

  /**
   * Track new {@linkplain Expirable} with a custom expiration callback
   *
   * @param onExpired expiration callback
   * @return a new {@linkplain Expirable} instance or {@linkplain Expirable#EMPTY}
   */
  Expirable track(Runnable onExpired) {
    // shortcut if the expiration period is not set
    if (expirationNs <= 0) {
      return Expirable.EMPTY;
    }
    // shortcut if the tracker is closed
    if (closed) {
      return Expirable.EMPTY;
    }

    long nanos = timeSource.getNanos();
    long thisSlot = getTimeSlot(nanos);
    long previousSlot = LAST_ACCESSED_SLOT_UPDATER.getAndSet(this, thisSlot);
    int toWriteIdx = getBucketIndex(thisSlot);
    if (previousSlot != thisSlot) {
      // the time slot moved forward - schedule the oldest slot for expiration
      if (!expireBucketAt(toWriteIdx)) {
        // Failed to schedule the oldest slot for expiration due to capacity constraints.
        // Can not track any more expirables.
        return Expirable.EMPTY;
      }
    }
    return buckets[toWriteIdx].add(nanos, expirationNs, onExpired);
  }

  boolean expireBucketAt(int toWriteIdx) {
    StripedBucket toClean = buckets[toWriteIdx < numBuckets - 1 ? toWriteIdx + 1 : 0];
    if (cleanupQueue != null) {
      if (!cleanupQueue.relaxedOffer(toClean)) {
        int cnt = 0;
        long start = timeSource.getNanos();
        while (!cleanupQueue.relaxedOffer(toClean)) {
          Thread.yield();
          // avoid accessing the volatile field and reading timestamp every single iteration
          if ((cnt = (cnt + 1) % 1000) == 0) {
            if (closed) {
              // bail out if the tracker was closed in the meantime
              return false;
            }
            if (timeSource.getNanos() - start > 500_000_000L) { // 500ms limit
              // something went terribly wrong and the cleanup thread is most probably dead
              close();
              return false;
            }
          }
        }
      }
    }
    return true;
  }

  /**
   * Close the tracker.<br>
   * Stop the cleanup thread and release all held resources.
   */
  @Override
  public void close() {
    if (scheduledTask != null) {
      cleanupQueue.clear();
      scheduledTask.cancel();
    }
    closed = true;
  }

  int getBucketIndex(long slot) {
    int idx = (int) slot % numBuckets;
    return idx >= 0 ? idx : (numBuckets + idx);
  }

  long getTimeSlot(long nanos) {
    return (long) Math.ceil(nanos / (double) granularityNs);
  }

  int capacity() {
    return capacity;
  }

  int bucketCapacity() {
    return bucketCapacity;
  }
}

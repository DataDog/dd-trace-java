package com.datadog.profiling.context;

import datadog.trace.api.function.Consumer;
import datadog.trace.util.AgentTaskScheduler;
import org.jctools.queues.MpscArrayQueue;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

final class ExpirationTracker implements Closeable {
  static final class Expirable {
    private static final Consumer<Expirable> NOOP_CONSUMER = x -> {};
    static final Expirable EMPTY = new Expirable(-1, -1);

    private volatile Consumer<Expirable> onExpired;
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<Expirable, Consumer> ON_EXPIRED_UPDATER = AtomicReferenceFieldUpdater.newUpdater(Expirable.class, Consumer.class, "onExpired");
    private final long expiration;
    private volatile long nanos;
    private static final AtomicLongFieldUpdater<Expirable> NANOS_UPDATER = AtomicLongFieldUpdater.newUpdater(Expirable.class, "nanos");

    private final Bucket bucket;
    private final int pos;

    private Expirable(long ts, long expiration) {
      this(null, -1, ts, expiration, NOOP_CONSUMER);
    }

    Expirable(Bucket bucket, int pos, long nanos, long expiration, Consumer<Expirable> onExpired) {
      this.bucket = bucket;
      this.pos = pos;
      this.onExpired = onExpired;
      this.nanos = nanos;
      this.expiration = expiration;
    }

    void setOnExpiredCallback(Consumer<Expirable> callback) {
      this.onExpired = callback;
    }

    public void touch(long nanos) {
      NANOS_UPDATER.set(this, nanos);
    }

    @SuppressWarnings("unchecked")
    void expire() {
      Consumer<Expirable> callback = ON_EXPIRED_UPDATER.getAndSet(this, null);
      if (callback != null) {
        synchronized (bucket) {
          bucket.remove(pos);
        }
        try {
          callback.accept(this);
        } catch (Throwable ignored) {
        }
      }
    }

    boolean isExpired() {
      return onExpired == null;
    }

    boolean isExpiring(long nowNs) {
      return onExpired != null && expiration > 0 && (nowNs - nanos) >= expiration;
    }

    boolean hasExpiration() {
      return expiration > -1;
    }
  }

  static final class Bucket {
    interface Callback {
      Callback EMPTY = new Callback() {
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

    private boolean fillupTriggered = false;

    Bucket(int capacity, Callback fillupCallback) {
      data = new Expirable[capacity];
      this.fillupCallback = fillupCallback;
    }

    Expirable add(long nanos, long expiration, Consumer<Expirable> onExpired) {
      if (size == data.length) {
        return Expirable.EMPTY;
      }
      if (size == data.length - 1) {
        if (!fillupTriggered) {
          try {
            fillupCallback.onBucketFull();
          } catch (Throwable ignored) {
          } finally {
            fillupTriggered = true;
          }
        }
      }
      int pos = size;
      Expirable e = new Expirable(this, pos, nanos, expiration, onExpired);
      data[size++] = e;
      return e;
    }

    private void remove(int pos) {
      if (pos > -1) {
        data[pos] = data[--size];
        data[size] = null;

        if (fillupTriggered) {
          try {
            fillupCallback.onBucketAvailable();
          } catch (Throwable ignored) {
          } finally {
            fillupTriggered = false;
          }
        }
      }
    }

    boolean clean(long nanos) {
      int reinsertionIdx = 0;
      for (int i =0; i < data.length; i++) {
        if (data[i] == null) {
          // the data is auto-compacted so first 'null' element means all the followup ones are null also
          break;
        }
        Expirable e = data[i];
        if (e.isExpiring(nanos)) {
          try {
            e.onExpired.accept(e);
          } catch (Throwable ignored) {
          } finally {
            data[i] = null;
          }
        } else {
          if (reinsertionIdx != i) {
            data[reinsertionIdx] = data[i];
            data[i] = null;
          }
          // if reinsertionIdx == i the data stay in its place so data[i] is not be nulled
          reinsertionIdx++;
        }
      }
      size = reinsertionIdx;
      if (fillupTriggered && size < data.length) {
        try {
          fillupCallback.onBucketAvailable();
        } catch (Throwable ignored) {
        } finally {
          fillupTriggered = false;
        }
      }
      // set the tainted status if there were any reinsertions
      int tainted = reinsertionIdx;
      return tainted > 0;
    }
  }

  static final class BucketAvailabilityTracker implements Bucket.Callback {
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
      availableBuckets.accumulateAndGet(1, (cur, v) -> cur > 0 ? cur - v : 0);
    }

    @Override
    public void onBucketAvailable() {
      availableBuckets.accumulateAndGet(1, (cur, v) -> cur < buckets ? cur + v : buckets);
    }
  }

  @SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter"})
  static final class StripedBucket {
    private final Bucket[] buckets;

    private final int numStripes;

    private final BucketAvailabilityTracker availabilityTracker;

    private long lastCleanupTs = -1;

    StripedBucket(int parallelism, int capacity) {
      this.numStripes = parallelism * 2;
      buckets = new Bucket[numStripes];
      int bucketCapacity = (int) Math.ceil((double) capacity / numStripes);
      availabilityTracker = new BucketAvailabilityTracker(numStripes);
      for (int i = 0; i < numStripes; i++) {
        buckets[i] = new Bucket(bucketCapacity, availabilityTracker);
      }
    }

    Expirable add(long nanos, long expiration, Runnable onExpired) {
      if (!availabilityTracker.hasAvailableBuckets()) {
        return Expirable.EMPTY;
      }

      int stripe = ThreadLocalRandom.current().nextInt(numStripes);

      for (int i = 0; i < numStripes; i++) {
        Bucket bucket = buckets[stripe];
        // Synchronized is performing better than locks here because we are striving
        // for as low contention as possible by striping.
        // Instead of locking buckets we could use dedicated reentrant locks but
        // for ~50% chance of contention when all threads are accessing the striped bucket
        // the throughput is almost 50% higher for synchronized than for reentrant locks.
        synchronized (bucket) {
          Expirable e = bucket.add(nanos, expiration, t -> onExpired.run());
          if (e != Expirable.EMPTY) {
            return e;
          }
          stripe = (stripe + 11) % numStripes;
        }
      }
      return Expirable.EMPTY;
    }

    /**
     * Cleans up the bucket by expiring all eligible elements.<br>
     * This method should always be called from the same thread and from only thread at a time -
     * otherwise the performancy may suffer because the same bucket could be cleaned up several times
     * @param nanos the operation timestamp
     * @return {@literal true} if the bucket was completely cleaned
     */
    boolean clean(long nanos) {
      if (lastCleanupTs ==  nanos) {
        return true; // already processed in this epoch
      }
      boolean tainted = false;
      for (Bucket bucket : buckets) {
        synchronized (bucket) {
          tainted = bucket.clean(nanos) || tainted;
        }
      }
      lastCleanupTs = nanos;
      return !tainted;
    }
  }

  @FunctionalInterface
  interface NanoTimeSource {
    long getNanos();
  }

  private final int capacity;
  private final int tickCapacity;
  private final StripedBucket[] buckets;
  private final int numBuckets;
  private final TimeUnit timeUnit;
  private final long expiration;
  private final long expirationNs;

  private final long granularity;
  private final long granularityNs;

  private static final AtomicLongFieldUpdater<ExpirationTracker> LAST_ACCESS_TS_UPDATER = AtomicLongFieldUpdater.newUpdater(ExpirationTracker.class, "lastAccessTs");
  private volatile long lastAccessTs = -1;
  private volatile boolean closed = false;

  private final MpscArrayQueue<StripedBucket> cleanupQueue;
  private final AgentTaskScheduler.Scheduled<ExpirationTracker> scheduledTask;
  private final NanoTimeSource timeSource;

  ExpirationTracker(long expiration, long granularity, TimeUnit timeUnit, int parallelism, int capacity) {
    this(expiration, granularity, timeUnit, parallelism, capacity, System::nanoTime, true);
  }

  ExpirationTracker(long expiration, long granularity, TimeUnit timeUnit, int parallelism, int capacity, NanoTimeSource timeSource, boolean scheduleCleanup) {
    if (expiration > -1 && expiration <= granularity) {
      throw new IllegalArgumentException("'expiration' must be larger than 'granularity'");
    }

    this.timeUnit = timeUnit;
    this.expiration = expiration;
    this.expirationNs = timeUnit.toNanos(expiration);
    this.granularity = granularity;
    this.granularityNs = timeUnit.toNanos(granularity);
    this.timeSource = timeSource;

    numBuckets = (int)(this.expiration / this.granularity) + 1;
    buckets = new StripedBucket[numBuckets];
    tickCapacity = (int)Math.ceil(capacity / (double)numBuckets);

    this.capacity = numBuckets * tickCapacity;
    for (int i = 0; i < numBuckets; i++) {
      buckets[i] = new StripedBucket(parallelism, tickCapacity);
    }
    cleanupQueue = new MpscArrayQueue<>(numBuckets * 2);
    if (scheduleCleanup) {
      int schedulePeriod = Math.max((int)(granularityNs / 2d), 1);
      scheduledTask = AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(ExpirationTracker::processCleanup, this, schedulePeriod, schedulePeriod, timeUnit);
    } else {
      scheduledTask = null;
    }
  }

  final List<StripedBucket> survivors = new ArrayList<>();
  private long lastCleanupSlot = -1;

  void processCleanup() {
    long nanos = timeSource.getNanos();
    long slot = (long)Math.ceil(nanos / (double)granularityNs);
    try {
      if (slot > lastCleanupSlot) {
        // first process the expired queue
        int processed = cleanupQueue.drain(b -> {
          if (!b.clean(slot)) {
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

  Expirable track() {
    return track(() -> {});
  }

  Expirable track(Runnable onExpired) {
    long nanos = timeSource.getNanos();
    if (expirationNs <= 0) {
      return Expirable.EMPTY;
    }
    if (closed) {
      return Expirable.EMPTY;
    }

    long thisSlot = (long)Math.ceil(nanos / (double)granularityNs);
    long previousSlot = LAST_ACCESS_TS_UPDATER.getAndSet(this, thisSlot);
    int toWriteIdx = getBucketIndex(thisSlot);
    if (previousSlot != thisSlot) {
      if (!expireBucketAt(toWriteIdx)) {
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
              return false;
            }
            if (timeSource.getNanos() - start > 500_000_000L) {
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

  @Override
  public void close() {
    if (scheduledTask != null) {
      cleanupQueue.clear();
      scheduledTask.cancel();
    }
    closed = true;
  }

  int getBucketIndex(long slot) {
    int idx = (int)slot  %  numBuckets;
    return idx >= 0 ? idx : (numBuckets + idx);
  }

  int capacity() {
    return capacity;
  }

  int tickCapacity() {
    return tickCapacity;
  }
}

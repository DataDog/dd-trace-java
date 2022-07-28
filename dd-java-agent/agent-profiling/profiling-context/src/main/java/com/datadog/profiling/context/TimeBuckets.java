package com.datadog.profiling.context;

import datadog.trace.api.function.Consumer;
import datadog.trace.util.AgentTaskScheduler;
import org.jctools.queues.MpscArrayQueue;

import java.io.Closeable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

final class TimeBuckets implements Closeable {
  static final class Expiring {
    private static final Consumer<Expiring> NOOP_CONSUMER = x -> {};
    static final Expiring NOOP = new Expiring(-1, -1);

    private final Consumer<Expiring> onExpired;
    private final long expiration;
    private static final AtomicLongFieldUpdater<Expiring> EXPIRATION_TS_UPDATER = AtomicLongFieldUpdater.newUpdater(Expiring.class, "expirationTs");
    private volatile long expirationTs;

    private final Bucket bucket;
    private final int pos;

    private Expiring(long ts, long expiration) {
      this(null, -1, ts, expiration, NOOP_CONSUMER);
    }

    Expiring(Bucket bucket, int pos, long ts, long expiration, Consumer<Expiring> onExpired) {
      this.bucket = bucket;
      this.pos = pos;
      this.onExpired = onExpired;
      this.expiration = expiration;
      this.expirationTs = ts + expiration;
    }

    void touch(long ts) {
      long target = ts + expiration;
      EXPIRATION_TS_UPDATER.updateAndGet(this, prev -> Math.max(prev, target));
    }

    void expire() {
      synchronized (bucket) {
        bucket.remove(pos);
      }
      try {
        onExpired.accept(this);
      } catch (Throwable ignored) {}
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
    private final Expiring[] data;
    private final Callback fillupCallback;

    private boolean fillupTriggered = false;

    Bucket(int capacity) {
      this(capacity, Callback.EMPTY);
    }

    Bucket(int capacity, Callback fillupCallback) {
      data = new Expiring[capacity];
      this.fillupCallback = fillupCallback;
    }

    Expiring add(long ts, long expiration, Consumer<Expiring> onExpired) {
      if (size == data.length) {
        if (!fillupTriggered) {
          try {
            fillupCallback.onBucketFull();
          } catch (Throwable ignored) {
          } finally {
            fillupTriggered = true;
          }
        }
        return Expiring.NOOP;
      }
      int pos = size;
      Expiring e = new Expiring(this, pos, ts, expiration, onExpired);
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

    void clean(long ts) {
      for (int i =0; i < data.length; i++) {
        if (data[i] == null) {
          continue;
        }
        Expiring e = data[i];
        if (data[i].expirationTs <= ts) {
          try {
            e.onExpired.accept(e);
          } catch (Throwable ignored) {
          }
        }
        data[i] = null;
      }
      size = 0;
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
    private final int bucketCapacity;

    private final BucketAvailabilityTracker availabilityTracker;

    StripedBucket(int parallelism, int capacity) {
      this.numStripes = parallelism * 2;
      buckets = new Bucket[numStripes];
      bucketCapacity = (int)Math.ceil((double)capacity / numStripes);
      availabilityTracker = new BucketAvailabilityTracker(numStripes);
      for (int i = 0; i < numStripes; i++) {
        buckets[i] = new Bucket(bucketCapacity, availabilityTracker);
      }
    }

    Expiring add(long ts, long expiration, Runnable onExpired) {
      if (!availabilityTracker.hasAvailableBuckets()) {
        return Expiring.NOOP;
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
          Expiring e = bucket.add(ts, expiration, t -> onExpired.run());
          if (e != Expiring.NOOP) {
            return e;
          }
          stripe = (stripe + 11) % numStripes;
        }
      }
      return Expiring.NOOP;
    }

    void clean(long ts) {
      for (Bucket bucket : buckets) {
        synchronized (bucket) {
          bucket.clean(ts);
        }
      }
    }
  }

  private final StripedBucket[] buckets;
  private final int numBuckets;
  private final TimeUnit timeUnit;
  private final long expiration;
  private final long granularity;

  private static final AtomicLongFieldUpdater<TimeBuckets> LAST_ACCESS_TS_UPDATER = AtomicLongFieldUpdater.newUpdater(TimeBuckets.class, "lastAccessTs");
  private volatile long lastAccessTs = -1;

  private final MpscArrayQueue<StripedBucket> cleanupQueue;
  private final AgentTaskScheduler.Scheduled<TimeBuckets> scheduledTask;

  TimeBuckets(long expiration, long granularity, TimeUnit timeUnit, int parallelism, int capacity) {
    this(expiration, granularity, timeUnit, parallelism, capacity, true);
  }

  TimeBuckets(long expiration, long granularity, TimeUnit timeUnit, int parallelism, int capacity, boolean scheduleCleanup) {
    if (expiration <= granularity) {
      throw new IllegalArgumentException("'expiration' must be larger than 'granularity'");
    }

    this.timeUnit = timeUnit;
    this.expiration = expiration;
    this.granularity = granularity;

    numBuckets = (int)(this.expiration / this.granularity) + 1;
    buckets = new StripedBucket[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      buckets[i] = new StripedBucket(parallelism, (int)Math.ceil(capacity / (double)numBuckets));
    }
    if (scheduleCleanup) {
      cleanupQueue = new MpscArrayQueue<>(numBuckets);
      int schedulePeriod = Math.max((int)(granularity / 2), 1);
      scheduledTask = AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(TimeBuckets::processCleanup, this, schedulePeriod, schedulePeriod, timeUnit);
    } else {
      cleanupQueue = null;
      scheduledTask = null;
    }
  }

  private void processCleanup() {
    long ts = TimeUnit.NANOSECONDS.convert(System.nanoTime(), timeUnit);
    // first process the expired queue
    cleanupQueue.drain(b -> b.clean(ts));
    // also, expire the bucket related to the current timestamp
    int toWriteIdx = getBucketIndex(ts);
    buckets[toWriteIdx < numBuckets - 1 ? toWriteIdx + 1 : 0].clean(ts);
  }

  Expiring add(long ts, Runnable onExpired) {
    long previousTs = LAST_ACCESS_TS_UPDATER.getAndSet(this, ts);
    int toWriteIdx = getBucketIndex(ts);
    if (previousTs != ts) {
      expireBucket(toWriteIdx);
    }
    return buckets[toWriteIdx].add(ts, expiration, onExpired);
  }

  void expireBucket(long ts) {
    int toWriteIdx = getBucketIndex(ts);
    StripedBucket toClean = buckets[toWriteIdx < numBuckets - 1 ? toWriteIdx + 1 : 0];
    toClean.clean(ts);
  }

  private void expireBucket(int toWriteIdx) {
    StripedBucket toClean = buckets[toWriteIdx < numBuckets - 1 ? toWriteIdx + 1 : 0];
    if (cleanupQueue != null) {
      while (!cleanupQueue.relaxedOffer(toClean)) {
        Thread.yield();
      }
    }
  }

  @Override
  public void close() {
    if (scheduledTask != null) {
      scheduledTask.cancel();
    }
  }

  int getBucketIndex(long tsSecs) {
    return (int)Math.ceil(tsSecs / (double)granularity) %  numBuckets;
  }
}

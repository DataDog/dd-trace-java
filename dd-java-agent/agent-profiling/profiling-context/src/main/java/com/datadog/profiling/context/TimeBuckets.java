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
      EXPIRATION_TS_UPDATER.updateAndGet(this, prev -> prev > -1 ? Math.max(prev, target) : -1);
    }

    void expire() {
      synchronized (bucket) {
        bucket.remove(pos);
      }
      try {
        onExpired.accept(this);
      } catch (Throwable ignored) {}
      EXPIRATION_TS_UPDATER.updateAndGet(this, prev -> -1);
    }

    boolean isExpired() {
      return expirationTs == -1;
    }

    boolean isExpiring(long ts) {
      long targetTs = expirationTs;
      return targetTs > -1 && targetTs <= ts;
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

    Bucket(int capacity, Callback fillupCallback) {
      data = new Expiring[capacity];
      this.fillupCallback = fillupCallback;
    }

    Expiring add(long ts, long expiration, Consumer<Expiring> onExpired) {
      if (size == data.length) {
        return Expiring.NOOP;
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

    boolean clean(long ts) {
      int reinsertionIdx = 0;
      for (int i =0; i < data.length; i++) {
        if (data[i] == null) {
          // the data is auto-compacted so first 'null' element means all the followup ones are null also
          break;
        }
        Expiring e = data[i];
        if (e.isExpiring(ts)) {
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

    /**
     * Cleans up the bucket by expiring all eligible elements.<br>
     * This method should always be called from the same thread and from only thread at a time -
     * otherwise the performancy may suffer because the same bucket could be cleaned up several times
     * @param ts the operation timestamp
     * @return {@literal true} if the bucket was completely cleaned
     */
    boolean clean(long ts) {
      if (lastCleanupTs ==  ts) {
        return true; // already processed in this epoch
      }
      boolean tainted = false;
      for (Bucket bucket : buckets) {
        synchronized (bucket) {
          tainted = bucket.clean(ts) || tainted;
        }
      }
      lastCleanupTs = ts;
      return !tainted;
    }
  }

  private final int capacity;
  private final int tickCapacity;
  private final StripedBucket[] buckets;
  private final int numBuckets;
  private final TimeUnit timeUnit;
  private final long expiration;
  private final long granularity;

  private static final AtomicLongFieldUpdater<TimeBuckets> LAST_ACCESS_TS_UPDATER = AtomicLongFieldUpdater.newUpdater(TimeBuckets.class, "lastAccessTs");
  private volatile long lastAccessTs = -1;
  private volatile boolean closed = false;

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
    tickCapacity = (int)Math.ceil(capacity / (double)numBuckets);

    this.capacity = numBuckets * tickCapacity;
    for (int i = 0; i < numBuckets; i++) {
      buckets[i] = new StripedBucket(parallelism, tickCapacity);
    }
    cleanupQueue = new MpscArrayQueue<>(numBuckets * 2);
    if (scheduleCleanup) {
      int schedulePeriod = Math.max((int)(granularity / 2), 1);
      scheduledTask = AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(TimeBuckets::processCleanup, this, schedulePeriod, schedulePeriod, timeUnit);
    } else {
      scheduledTask = null;
    }
  }

  final List<StripedBucket> survivors = new ArrayList<>();
  private long lastCleanupTs = -1;

  private void processCleanup() {
    processCleanup(timeUnit.convert(System.nanoTime(), TimeUnit.NANOSECONDS));
  }

  void processCleanup(long ts) {
    try {
      if (ts > lastCleanupTs) {
        // first process the expired queue
        int processed = cleanupQueue.drain(b -> {
          if (!b.clean(ts)) {
            survivors.add(b);
          }
        });
        // re-add the surviving buckets
        cleanupQueue.addAll(survivors);

        if (processed == 0) {
          // if not scheduled externally expire the bucket related to the current timestamp
          int toCleanIdx = getBucketIndex(ts);
          StripedBucket toClean = buckets[toCleanIdx];
          if (!toClean.clean(ts)) {
            // re-add the surviving bucket
            cleanupQueue.add(toClean);
          }
        }
      }
    } finally {
      lastCleanupTs = ts;
      survivors.clear();
    }
  }

  Expiring add(long ts, Runnable onExpired) {
    if (closed) {
      return Expiring.NOOP;
    }
    long previousTs = LAST_ACCESS_TS_UPDATER.getAndSet(this, ts);
    int toWriteIdx = getBucketIndex(ts);
    if (previousTs != ts) {
      if (!expireBucketAt(ts, toWriteIdx)) {
        return Expiring.NOOP;
      }
    }
    return buckets[toWriteIdx].add(ts, expiration, onExpired);
  }

  void expireBucket(long ts) {
    expireBucketAt(ts, getBucketIndex(ts));
  }

  private boolean expireBucketAt(long ts, int toWriteIdx) {
    StripedBucket toClean = buckets[toWriteIdx < numBuckets - 1 ? toWriteIdx + 1 : 0];
    if (cleanupQueue != null) {
      if (!cleanupQueue.relaxedOffer(toClean)) {
        int cnt = 0;
        long start = System.nanoTime();
        while (!cleanupQueue.relaxedOffer(toClean)) {
          Thread.yield();
          if ((cnt = (cnt + 1) % 1000) == 0) {
            if (closed) {
              return false;
            }
            if (System.nanoTime() - start > 500_000_000L) {
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

  int getBucketIndex(long tsSecs) {
    return (int)Math.ceil(tsSecs / (double)granularity) %  numBuckets;
  }

  int capacity() {
    return capacity;
  }

  int tickCapacity() {
    return tickCapacity;
  }
}

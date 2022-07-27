package com.datadog.profiling.context;

import datadog.trace.util.AgentTaskScheduler;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ExpirationTracker<T, E extends ExpirationTracker.Expirable<T>> implements AutoCloseable {
  interface Expirable<T> {
    Expiring<T> asExpiring();
  }

  abstract static class Expiring<T> implements Comparable<Expiring<T>> {
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    volatile T payload;

    final long inception;
    final long ttl;
    final long originalExpiration;
    volatile long expiration;

    private final int identityHashCode;

    Expiring(T payload, long inception, long ttl) {
      this.payload = payload;
      this.inception = inception;
      this.ttl = ttl;
      this.originalExpiration = inception + ttl;
      this.expiration = originalExpiration;
      this.identityHashCode = COUNTER.getAndIncrement();
    }

    final void touch(long ts) {
      this.expiration = ts + ttl;
    }

    abstract boolean isActive();

    protected void onExpired() {}

    @Override
    public final int compareTo(Expiring<T> o) {
      assert o != null;

      long rightTs = o.originalExpiration;
      if (originalExpiration < rightTs) {
        return -1;
      }
      if (originalExpiration > rightTs) {
        return 1;
      }
      return Integer.compare(this.identityHashCode, o.identityHashCode);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(ExpirationTracker.class);

  private static final int STRIPES = 128;

  @SuppressWarnings("unchecked")
  private final Map<Expiring<T>, Object>[] trackingMap = new Map[STRIPES];

  private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

  private final int capacity;
  private final long latencyLimit;

  private final AgentTaskScheduler.Scheduled<ExpirationTracker<T, E>> cleanupTask;

  public ExpirationTracker(int capacity, long inactivityCheckMs, long latencyLimitNs) {
    this.capacity = (capacity / STRIPES) + 1;
    for (int i = 0; i < STRIPES; i++) {
      trackingMap[i] = new TreeMap<>();
      locks[i] = new ReentrantLock();
    }
    this.latencyLimit = latencyLimitNs;
    cleanupTask = AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
        new AgentTaskScheduler.Task<ExpirationTracker<T, E>>() {
          private static final int step = 11;

          @Override
          public void run(ExpirationTracker<T, E> target) {
            int stripe = ThreadLocalRandom.current().nextInt(STRIPES);
            long now = System.nanoTime();
            for (int i = 0; i < STRIPES; i++) {
              ReentrantLock lock = target.locks[stripe];
              lock.lock();
              try {
                cleanupExpired(now, target.trackingMap[stripe]);
              } finally {
                lock.unlock();
                stripe = (stripe + step) % STRIPES;
              }
            }
          }
        },
        this,
        inactivityCheckMs,
        inactivityCheckMs,
        TimeUnit.MILLISECONDS);
  }

  public boolean track(E instance) throws InterruptedException {
    Expiring<T> delayed = instance.asExpiring();
    long limitTs = -1;
    long now = -1;
    int stripe = ThreadLocalRandom.current().nextInt(STRIPES);
    do {
      try {
        locks[stripe].lockInterruptibly();
        Map<Expiring<T>, Object> map = trackingMap[stripe];
        if (map.size() < capacity) {
          map.put(delayed, null);
          return true;
        }

        now = System.nanoTime();
        if (limitTs == -1) {
          limitTs = now + latencyLimit;
        }
        cleanupInactive(now, map, limitTs);
        if (map.size() < capacity) {
          map.put(delayed, null);
          return true;
        }
      } finally {
        locks[stripe].unlock();
      }
      stripe = (stripe + 7) % STRIPES;
    } while (limitTs <= now);
    log.warn("Unable to track an element without violating capacity constraints");
    return false;
  }

  @Override
  public void close() {
    cleanupTask.cancel();
  }

  private void cleanupExpired(long now, Map<Expiring<T>, Object> map) {
    Iterator<Expiring<T>> iter = map.keySet().iterator();
    while (iter.hasNext()) {
      Expiring<T> d = iter.next();
      if (d.expiration > now) {
        if (d.originalExpiration > now) {
          break;
        } else {
          // the expiration has been extended - skip the element
          continue;
        }
      }
      try {
        d.onExpired();
      } catch (Throwable t) {
        log.error("Error while expiring instance {}", d, t);
      }
      iter.remove();
    }
  }

  private void cleanupInactive(long now, Map<Expiring<T>, Object> map, long limit) {
    int counter = 0;
    Iterator<Expiring<T>> iter = map.keySet().iterator();
    while (iter.hasNext()) {
      Expiring<T> d = iter.next();
      if (!d.isActive() || d.expiration > now) {
        try {
          d.onExpired();
        } catch (Throwable t) {
          log.error("Error while expiring instance {}", d, t);
        }
        iter.remove();
      }
      if ((++counter % 1000) == 0) {
        if (System.nanoTime() > limit) {
          break;
        }
      }
    }
  }
}

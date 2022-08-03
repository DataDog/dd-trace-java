package com.datadog.profiling.context;

import datadog.trace.api.function.Consumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("rawtypes")
public final class ThreadMap<T> {
  private static final Logger log = LoggerFactory.getLogger(ThreadMap.class);

  private final int stripes;
  private final ReadWriteLock[] locks;
  private final Long2ObjectMap[][] maps;

  private volatile int mapsRefPtr = 1;
  private static final AtomicIntegerFieldUpdater<ThreadMap> MAPS_REF_PTR_UPDATER = AtomicIntegerFieldUpdater.newUpdater(ThreadMap.class, "mapsRefPtr");
  private volatile Long2ObjectMap[] mapsRef;
  private static final AtomicReferenceFieldUpdater<ThreadMap, Long2ObjectMap[]> MAPS_REF_UPDATER = AtomicReferenceFieldUpdater.newUpdater(ThreadMap.class, Long2ObjectMap[].class, "mapsRef");

  private volatile int writerTracker = 0;
  private static final AtomicIntegerFieldUpdater<ThreadMap> WRITE_TRACKER_UPDATER = AtomicIntegerFieldUpdater.newUpdater(ThreadMap.class, "writerTracker");

  public ThreadMap() {
    this(Runtime.getRuntime().availableProcessors() * 2);
  }

  public ThreadMap(int stripes) {
    this.stripes = stripes;
    locks = new ReadWriteLock[stripes];
    maps = new Long2ObjectRBTreeMap[2][stripes];
    for (int i = 0; i < stripes; i++) {
      locks[i] = new ReentrantReadWriteLock();
      maps[0][i] = new Long2ObjectRBTreeMap<T>();
      maps[1][i] = new Long2ObjectRBTreeMap<T>();
    }
    mapsRef = maps[0];
  }

  @SuppressWarnings("unchecked")
  public T get(long threadId) {
    int stripe = getStripe(threadId);
    Lock lock = locks[stripe].readLock();

    while (!lock.tryLock()) {
      Thread.yield();
    }
    try {
      return (T) mapsRef[stripe].get(threadId);
    } finally {
      lock.unlock();
    }
  }

  @SuppressWarnings("unchecked")
  public T put(long threadId, T value) {
    int writers = -1;
    while ((writers = WRITE_TRACKER_UPDATER.updateAndGet(this, w -> w >= 0 ? w + 1 : w)) < 0) {
      Thread.yield();
    }
    try {
      int stripe = getStripe(threadId);
      Lock lock = locks[stripe].writeLock();

      while (!lock.tryLock()) {
        Thread.yield();
      }
      try {
        return (T) mapsRef[stripe].put(threadId, value);
      } finally {
        lock.unlock();
      }
    } finally {
      WRITE_TRACKER_UPDATER.compareAndSet(this, writers, writers - 1);
    }
  }

  public void clear() {
    clear(null);
  }

  @SuppressWarnings("unchecked")
  public void clear(Consumer<T> cleanup) {
    int writer = 0;
    while ((writer = WRITE_TRACKER_UPDATER.getAndUpdate(this, w -> w == 0 ? -1 : w)) > 0) {
      LockSupport.parkNanos(50_000L);
    }
    try {
      for (int p = 0; p < 2; p++) {
        for (int i = 0; i < stripes; i++) {
          Long2ObjectMap<T> map = (Long2ObjectMap<T>) maps[p][i];
          if (map.isEmpty()) {
            continue;
          }
          if (cleanup != null) {
            for (Long2ObjectMap.Entry<T> entry : Long2ObjectMaps.fastIterable(map)) {
              try {
                cleanup.accept(entry.getValue());
              } catch (Throwable ignored) {
              }
            }
          }
          map.clear();
        }
      }
    } finally {
      WRITE_TRACKER_UPDATER.compareAndSet(this, -1, writer);
    }
  }

  @SuppressWarnings("unchecked")
  public void snapshot(Consumer<LongMapEntry<T>> onEntry) {
    int writer = 0;
    while ((writer = WRITE_TRACKER_UPDATER.getAndUpdate(this, w -> w == 0 ? -1 : w)) > 0) {
      LockSupport.parkNanos(50_000L);
    }
    int orgPtr = Integer.MIN_VALUE;
    int newPtr = Integer.MIN_VALUE;
    try {
      Long2ObjectMap[] ref;
      try {
        int ptr = -1;
        while ((ptr = MAPS_REF_PTR_UPDATER.getAndUpdate(this, p -> p > 0 ? -p : p)) < 0) {
          LockSupport.parkNanos(50_000L);
        }
        orgPtr = ptr;
        newPtr = (ptr  % 2) + 1;

        ref = MAPS_REF_UPDATER.getAndSet(this, maps[newPtr - 1]);
      } finally {
        WRITE_TRACKER_UPDATER.compareAndSet(this, -1, writer);
      }
      for (int i = 0; i < stripes; i++) {
        if (ref[i].isEmpty()) {
          continue;
        }
        if (onEntry != null) {
          for (Long2ObjectMap.Entry<T> entry : Long2ObjectMaps.fastIterable((Long2ObjectMap<T>) ref[i])) {
            try {
              onEntry.accept(new LongMapEntry<>(entry.getLongKey(), entry.getValue()));
            } catch (Throwable ignored) {
            }
          }
        }
        ref[i].clear();
      }
    } finally {
      if (orgPtr > Integer.MIN_VALUE) {
        MAPS_REF_PTR_UPDATER.compareAndSet(this, -orgPtr, newPtr);
      }
    }
  }

  private int getStripe(long key) {
    int hash = (int)(key ^ (key >>> 32));
    return hash != Integer.MIN_VALUE ? hash % stripes : 0;
  }
}

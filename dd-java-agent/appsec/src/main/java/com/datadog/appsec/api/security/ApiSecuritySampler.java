package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.util.AgentTaskScheduler;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal map for API Security sampling.
 * See "[RFC-1021] API Security Sampling Algorithm for thread-based concurrency".
 */
final public class ApiSecuritySampler {

  private static final int DEFAULT_MAX_ITEM_COUNT = 4096;
  private static final int DEFAULT_INTERVAL_SECONDS = 30;

  private final MonotonicClock clock;
  private final Executor executor;
  private final int intervalSeconds;
  private final AtomicReference<Table> table;
  private final AtomicBoolean rebuild = new AtomicBoolean(false);
  private final long zero;
  private final long maxItemCount;

  public ApiSecuritySampler() {
    this(DEFAULT_MAX_ITEM_COUNT, DEFAULT_INTERVAL_SECONDS, new Random().nextLong(), new DefaultMonotonicClock(), AgentTaskScheduler.INSTANCE);
  }

  public ApiSecuritySampler(final int maxItemCount, final int intervalSeconds, final long zero, final MonotonicClock clock, Executor executor) {
    table = new AtomicReference<>(new Table(maxItemCount));
    this.maxItemCount = maxItemCount;
    this.intervalSeconds = intervalSeconds;
    this.zero = zero;
    this.clock = clock != null ? clock : new DefaultMonotonicClock();
    this.executor = executor != null ? executor : AgentTaskScheduler.INSTANCE;
  }

  public boolean sample(AppSecRequestContext ctx) {
    final String route = ctx.getRoute();
    if (route == null) {
      return false;
    }
    final String method = ctx.getMethod();
    if (method == null) {
      return false;
    }
    final int statusCode = ctx.getResponseStatus();
    if (statusCode <= 0) {
      return false;
    }
    final long hash = computeApiHash(route, method, statusCode);
    return sample(hash);
  }

  public boolean sample(long key) {
    if (key == 0L) {
      key = zero;
    }
    final int now = clock.now();
    final Table table = this.table.get();
    Table.FindSlotResult findSlotResult;
    while (true) {
      findSlotResult = table.findSlot(key);
      if (!findSlotResult.exists) {
        final int newCount = table.count.incrementAndGet();
        if (newCount > maxItemCount && rebuild.compareAndSet(false, true)) {
          runRebuild();
        }
        if (newCount > maxItemCount * 2) {
          table.count.decrementAndGet();
          return false;
        }
        if (!findSlotResult.entry.key.compareAndSet(0, key)) {
          if (findSlotResult.entry.key.get() == key) {
            // Another thread just added this entry
            return false;
          }
          // This entry was just claimed for another key, try another slot.
          table.count.decrementAndGet();
          continue;
        }
        final long newEntryData = buildDataEntry(now, now);
        if (findSlotResult.entry.data.compareAndSet(0, newEntryData)) {
          return true;
        } else {
          return false;
        }
      }
      break;
    }
    long curData = findSlotResult.entry.data.get();
    final int stime = getStime(curData);
    final int deadline = now - intervalSeconds;
    if (stime <= deadline) {
      final long newData = buildDataEntry(now, now);
      while (!findSlotResult.entry.data.compareAndSet(curData, newData)) {
        curData = findSlotResult.entry.data.get();
        if (getStime(curData) == getAtime(curData)) {
          // Another thread just issued a keep decision
          return false;
        }
        if (getStime(curData) > now) {
          // Another thread is in our fugure, but did not issue a keep decision.
          return true;
        }
      }
      return true;
    }
    final long newData = buildDataEntry(getStime(curData), now);
    while (getAtime(curData) < now) {
      if (!findSlotResult.entry.data.compareAndSet(curData, newData)) {
        curData = findSlotResult.entry.data.get();
      }
    }
    return false;
  }

  private void runRebuild() {
    // TODO
  }

  private static class Table {
    private final Entry[] table;
    private final AtomicInteger count = new AtomicInteger(0);
    private final int maxItemCount;

    public Table(int maxItemCount) {
      this.maxItemCount = maxItemCount;
      final int size = 2 * maxItemCount + 1;
      table = new Entry[size];
      for (int i = 0; i < size; i++) {
        table[i] = new Entry();
      }
    }

    public FindSlotResult findSlot(final long key) {
      final int startIndex = (int) (key % (2L * maxItemCount));
      int index = startIndex;
      do {
        final Entry slot = table[index];
        final long slotKey = slot.key.get();
        if (slotKey == key) {
          return new FindSlotResult(slot, true);
        } else if (slotKey == 0L) {
          return new FindSlotResult(slot, false);
        }
        index++;
        if (index >= table.length) {
          index = 0;
        }
      } while (index != startIndex);
      return new FindSlotResult(table[(int)(maxItemCount * 2)], false);
    }

    static class FindSlotResult {
      public final Entry entry;
      public final boolean exists;

      public FindSlotResult(final Entry entry, final boolean exists) {
        this.entry = entry;
        this.exists = exists;
      }
    }

    static class Entry {
      private final AtomicLong key = new AtomicLong(0L);
      private final AtomicLong data = new AtomicLong(0L);
    }
  }

  interface MonotonicClock {
    int now();
  }

  static class DefaultMonotonicClock implements MonotonicClock {
    @Override
    public int now() {
      return (int) (System.nanoTime() / 1_000_000);
    }
  }

  long buildDataEntry(final int stime, final int atime) {
    long result = stime;
    result <<= 32;
    result |= atime & 0xFFFFFFFFL;
    return result;
  }

  int getStime(final long data) {
    return (int) (data >> 32);
  }

  int getAtime(final long data) {
    return (int) (data & 0xFFFFFFFFL);
  }

  private long computeApiHash(final String route, final String method, final int statusCode) {
    long result = 17;
    result = 31 * result + route.hashCode();
    result = 31 * result + method.hashCode();
    result = 31 * result + statusCode;
    return result;
  }
}

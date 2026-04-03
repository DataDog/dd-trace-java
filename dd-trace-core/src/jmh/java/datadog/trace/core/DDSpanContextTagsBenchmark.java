package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.api.TagMap;
import java.util.concurrent.locks.StampedLock;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/*
Benchmark                                              Mode  Cnt     Score   Error   Units
DDSpanContextTagsBenchmark.getTag_stampedlock_1t      thrpt    2   484.867          ops/us
DDSpanContextTagsBenchmark.getTag_stampedlock_8t      thrpt    2  3615.378          ops/us
DDSpanContextTagsBenchmark.getTag_synchronized_1t     thrpt    2   260.032          ops/us
DDSpanContextTagsBenchmark.getTag_synchronized_8t     thrpt    2   172.344          ops/us
DDSpanContextTagsBenchmark.mixed_stampedlock_8t       thrpt    2    70.809          ops/us
DDSpanContextTagsBenchmark.mixed_synchronized_8t      thrpt    2    41.302          ops/us
DDSpanContextTagsBenchmark.setMetric_stampedlock_1t   thrpt    2   176.073          ops/us
DDSpanContextTagsBenchmark.setMetric_synchronized_1t  thrpt    2   163.873          ops/us
DDSpanContextTagsBenchmark.setTag_stampedlock_1t      thrpt    2   177.211          ops/us
DDSpanContextTagsBenchmark.setTag_stampedlock_4t      thrpt    2   676.775          ops/us
DDSpanContextTagsBenchmark.setTag_synchronized_1t     thrpt    2   167.168          ops/us
DDSpanContextTagsBenchmark.setTag_synchronized_4t     thrpt    2   619.135          ops/us
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(value = 1)
public class DDSpanContextTagsBenchmark {

  /** Simulate the way the Map is locked on DDSpanContext */
  static final class SynchronizedTagMap {
    private final TagMap tags;

    SynchronizedTagMap(int capacity) {
      tags = TagMap.create(capacity);
    }

    void setTag(String key, String value) {
      synchronized (tags) {
        tags.set(key, value);
      }
    }

    void setMetric(String key, long value) {
      synchronized (tags) {
        tags.set(key, value);
      }
    }

    Object getTag(String key) {
      synchronized (tags) {
        return tags.getObject(key);
      }
    }
  }

  static final class StampedLockTagMap {
    private final TagMap tags;
    private final StampedLock lock = new StampedLock();

    StampedLockTagMap(int capacity) {
      tags = TagMap.create(capacity);
    }

    void setTag(String key, String value) {
      long stamp = lock.writeLock();
      try {
        tags.set(key, value);
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    void setMetric(String key, long value) {
      long stamp = lock.writeLock();
      try {
        tags.set(key, value);
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    Object getTag(String key) {
      long stamp = lock.tryOptimisticRead();
      Object value = tags.getObject(key);
      if (!lock.validate(stamp)) {
        stamp = lock.readLock();
        try {
          value = tags.getObject(key);
        } finally {
          lock.unlockRead(stamp);
        }
      }
      return value;
    }
  }

  static final SynchronizedTagMap SYNC_TAGS = populate(new SynchronizedTagMap(8));
  static final StampedLockTagMap STAMPED_TAGS = populate(new StampedLockTagMap(8));

  // Per-thread write targets: no shared state between threads to isolate lock overhead
  static final ThreadLocal<SynchronizedTagMap> SYNC_THREAD =
      ThreadLocal.withInitial(() -> new SynchronizedTagMap(8));
  static final ThreadLocal<StampedLockTagMap> STAMPED_THREAD =
      ThreadLocal.withInitial(() -> new StampedLockTagMap(8));

  private static <T extends SynchronizedTagMap> T populate(T m) {
    m.setTag("http.url", "https://example.com/api/v1/users");
    m.setTag("http.method", "GET");
    m.setMetric("http.status_code", 200L);
    m.setMetric("_sampling_priority_v1", 1L);
    return m;
  }

  private static <T extends StampedLockTagMap> T populate(T m) {
    m.setTag("http.url", "https://example.com/api/v1/users");
    m.setTag("http.method", "GET");
    m.setMetric("http.status_code", 200L);
    m.setMetric("_sampling_priority_v1", 1L);
    return m;
  }

  // single threads

  @Benchmark
  @Threads(1)
  public void setTag_synchronized_1t() {
    SYNC_THREAD.get().setTag("db.statement", "SELECT 1");
  }

  @Benchmark
  @Threads(1)
  public void setTag_stampedlock_1t() {
    STAMPED_THREAD.get().setTag("db.statement", "SELECT 1");
  }

  @Benchmark
  @Threads(1)
  public void setMetric_synchronized_1t() {
    SYNC_THREAD.get().setMetric("http.status_code", 200L);
  }

  @Benchmark
  @Threads(1)
  public void setMetric_stampedlock_1t() {
    STAMPED_THREAD.get().setMetric("http.status_code", 200L);
  }

  @Benchmark
  @Threads(1)
  public Object getTag_synchronized_1t(Blackhole bh) {
    return SYNC_TAGS.getTag("http.url");
  }

  @Benchmark
  @Threads(1)
  public Object getTag_stampedlock_1t(Blackhole bh) {
    return STAMPED_TAGS.getTag("http.url");
  }

  @Benchmark
  @Threads(8)
  public Object getTag_synchronized_8t(Blackhole bh) {
    return SYNC_TAGS.getTag("http.url");
  }

  @Benchmark
  @Threads(8)
  public Object getTag_stampedlock_8t(Blackhole bh) {
    return STAMPED_TAGS.getTag("http.url");
  }

  // 4 concurrent writers

  @Benchmark
  @Threads(4)
  public void setTag_synchronized_4t() {
    SYNC_THREAD.get().setTag("db.statement", "SELECT 1");
  }

  @Benchmark
  @Threads(4)
  public void setTag_stampedlock_4t() {
    STAMPED_THREAD.get().setTag("db.statement", "SELECT 1");
  }

  // Mixed read+write, 8 threads

  @Benchmark
  @Threads(8)
  public Object mixed_synchronized_8t(Blackhole bh) {
    SYNC_TAGS.setTag("db.statement", "SELECT 1");
    return SYNC_TAGS.getTag("http.url");
  }

  @Benchmark
  @Threads(8)
  public Object mixed_stampedlock_8t(Blackhole bh) {
    STAMPED_TAGS.setTag("db.statement", "SELECT 1");
    return STAMPED_TAGS.getTag("http.url");
  }
}

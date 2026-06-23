package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares thread-safe map strategies for shared, concurrent composite-key lookups.
 *
 * <p>See {@link ThreadSafeMapD1Benchmark} for the single-key variant.
 *
 * <p>The table is shared across all threads ({@link Scope#Benchmark}) and pre-populated before the
 * measurement iteration — modelling the steady-state read-mostly pattern that the tracer uses (a
 * per-class or per-method instrumentation cache consulted on every invocation).
 *
 * <p>Strategies compared:
 *
 * <ul>
 *   <li>{@link ConcurrentHashtable.D2} — lock-free reads, no composite key allocation per lookup.
 *   <li>{@link ConcurrentHashMap} — striped locking, allocates a {@link Key2} wrapper per lookup.
 *   <li>{@link ConcurrentSkipListMap} — fully lock-free (CAS), but pays tree traversal and {@link
 *       Comparable} overhead; allocates {@link Key2} per lookup.
 *   <li>{@link Collections#synchronizedMap} wrapping {@link HashMap} — global lock on every
 *       operation; allocates {@link Key2} per lookup. Establishes the coarse-locking baseline.
 * </ul>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Threads(8)
public class ThreadSafeMapD2Benchmark {

  static final int N_KEYS = 64;
  static final int CAPACITY = 128;

  static final String[] SOURCE_K1 = new String[N_KEYS];
  static final Integer[] SOURCE_K2 = new Integer[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      SOURCE_K1[i] = "key-" + i;
      SOURCE_K2[i] = i * 31 + 17;
    }
  }

  static final class D2Entry extends Hashtable.D2.Entry<String, Integer> {
    final long value;

    D2Entry(String k1, Integer k2) {
      super(k1, k2);
      this.value = 1L;
    }
  }

  /** Composite key for map-based baselines. */
  static final class Key2 implements Comparable<Key2> {
    final String k1;
    final Integer k2;
    final int hash;

    Key2(String k1, Integer k2) {
      this.k1 = k1;
      this.k2 = k2;
      this.hash = Objects.hash(k1, k2);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Key2)) {
        return false;
      }
      Key2 other = (Key2) o;
      return Objects.equals(k1, other.k1) && Objects.equals(k2, other.k2);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public int compareTo(Key2 other) {
      int c = k1.compareTo(other.k1);
      return c != 0 ? c : k2.compareTo(other.k2);
    }
  }

  /**
   * Shared state ({@link Scope#Benchmark}): one instance of each map across all threads, modelling
   * a shared instrumentation cache.
   */
  @State(Scope.Benchmark)
  public static class SharedState {
    ConcurrentHashtable.D2<String, Integer, D2Entry> table;
    ConcurrentHashMap<Key2, Long> concurrentHashMap;
    ConcurrentSkipListMap<Key2, Long> skipListMap;
    Map<Key2, Long> synchronizedHashMap;

    @Setup(Level.Iteration)
    public void setUp() {
      table = new ConcurrentHashtable.D2<>(CAPACITY);
      concurrentHashMap = new ConcurrentHashMap<>(CAPACITY);
      skipListMap = new ConcurrentSkipListMap<>();
      synchronizedHashMap = Collections.synchronizedMap(new HashMap<>(CAPACITY));
      for (int i = 0; i < N_KEYS; ++i) {
        table.getOrCreate(SOURCE_K1[i], SOURCE_K2[i], D2Entry::new);
        Key2 key = new Key2(SOURCE_K1[i], SOURCE_K2[i]);
        concurrentHashMap.put(key, (long) i);
        skipListMap.put(key, (long) i);
        synchronizedHashMap.put(key, (long) i);
      }
    }
  }

  /** Per-thread cursor so each thread cycles through keys independently. */
  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;

    int next() {
      int i = cursor;
      cursor = (i + 1) & (N_KEYS - 1);
      return i;
    }
  }

  @Benchmark
  public D2Entry get_concurrentHashtable(SharedState s, ThreadState t) {
    int i = t.next();
    return s.table.get(SOURCE_K1[i], SOURCE_K2[i]);
  }

  @Benchmark
  public Long get_concurrentHashMap(SharedState s, ThreadState t) {
    int i = t.next();
    return s.concurrentHashMap.get(new Key2(SOURCE_K1[i], SOURCE_K2[i]));
  }

  @Benchmark
  public Long get_concurrentSkipListMap(SharedState s, ThreadState t) {
    int i = t.next();
    return s.skipListMap.get(new Key2(SOURCE_K1[i], SOURCE_K2[i]));
  }

  @Benchmark
  public Long get_synchronizedHashMap(SharedState s, ThreadState t) {
    int i = t.next();
    return s.synchronizedHashMap.get(new Key2(SOURCE_K1[i], SOURCE_K2[i]));
  }

  @Benchmark
  public D2Entry getOrCreate_concurrentHashtable(SharedState s, ThreadState t) {
    int i = t.next();
    return s.table.getOrCreate(SOURCE_K1[i], SOURCE_K2[i], D2Entry::new);
  }

  /**
   * get-first pattern for CHM to avoid capturing-lambda allocation on hits — the idiomatic
   * equivalent of D2.getOrCreate on a mostly-populated table.
   */
  @Benchmark
  public Long getOrCreate_concurrentHashMap(SharedState s, ThreadState t) {
    int i = t.next();
    Key2 key = new Key2(SOURCE_K1[i], SOURCE_K2[i]);
    Long existing = s.concurrentHashMap.get(key);
    if (existing != null) {
      return existing;
    }
    return s.concurrentHashMap.computeIfAbsent(key, k -> 0L);
  }

  /**
   * get-first pattern for synchronized HashMap. On hit: one lock acquire/release for get. On miss:
   * a second synchronized block for the double-checked put.
   */
  @Benchmark
  public Long getOrCreate_synchronizedHashMap(SharedState s, ThreadState t) {
    int i = t.next();
    Key2 key = new Key2(SOURCE_K1[i], SOURCE_K2[i]);
    Long existing = s.synchronizedHashMap.get(key);
    if (existing != null) {
      return existing;
    }
    synchronized (s.synchronizedHashMap) {
      return s.synchronizedHashMap.computeIfAbsent(key, k -> 0L);
    }
  }
}

package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
 * Measures steady-state single-key lookups in a shared, pre-populated table.
 *
 * <p>Compares {@link ConcurrentHashtable.D1}, {@link ConcurrentHashMap}, {@link
 * ConcurrentSkipListMap}, and a synchronized {@link HashMap}. The table is shared across all
 * threads ({@link Scope#Benchmark}) and pre-populated before the measurement iteration — modelling
 * the steady-state read-mostly pattern that the tracer uses (a per-class or per-method
 * instrumentation cache consulted on every invocation). The {@code getOrCreate} methods exercise
 * their hit paths because setup installs every key.
 *
 * <p>Lookups reuse the key instances installed during setup. {@code Objects.equals} therefore
 * returns on identity before invoking {@code equals}; the benchmark does not include the cost of
 * comparing distinct-but-equal keys ({@code ImmutableMapBenchmark} covers that path explicitly via
 * its {@code _sameKey} vs default variants). See {@link ThreadSafeMapD2Benchmark} for composite
 * keys.
 *
 * <p>Java 17 results ({@code @Fork(2)}, {@code @Threads(8)}, 64 pre-populated keys):
 *
 * <pre>{@code
 * Benchmark                             Score   Units
 * get_concurrentHashtable               1583   ops/us
 * get_concurrentHashMap                 1145   ops/us
 * get_concurrentSkipListMap              170   ops/us
 * get_synchronizedHashMap                 33   ops/us
 *
 * getOrCreate_concurrentHashtable       1450   ops/us
 * getOrCreate_concurrentHashMap         1125   ops/us
 * getOrCreate_synchronizedHashMap         31   ops/us
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>{@code ConcurrentHashtable} is ~38% faster than {@code ConcurrentHashMap} on {@code get}
 *       (1583 vs 1145 ops/us); avoids the hash-to-segment translation CHM pays even on its fast
 *       path.
 *   <li>{@code ConcurrentSkipListMap} is ~9× slower than {@code ConcurrentHashMap} — tree traversal
 *       cost is high even under lock-free CAS.
 *   <li>Synchronized {@code HashMap} is ~47× slower than {@code ConcurrentHashtable}; the global
 *       lock serializes all 8 threads.
 *   <li>{@code getOrCreate} is near-identical to {@code get} because all keys are pre-populated —
 *       the lock branch is never taken during measurement.
 * </ul>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Threads(8)
public class ThreadSafeMapD1Benchmark {

  static final int N_KEYS = 64;
  static final int CAPACITY = 128;

  static final String[] KEYS = new String[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      KEYS[i] = "key-" + i;
    }
  }

  static final class D1Entry extends ConcurrentHashtable.D1.Entry<String> {
    final long value;

    D1Entry(String key) {
      super(key);
      this.value = 1L;
    }
  }

  /**
   * Shared state ({@link Scope#Benchmark}): one instance of each map across all threads, modelling
   * a shared instrumentation cache.
   */
  @State(Scope.Benchmark)
  public static class SharedState {
    ConcurrentHashtable.D1<String, D1Entry> table;
    ConcurrentHashMap<String, Long> concurrentHashMap;
    ConcurrentSkipListMap<String, Long> skipListMap;
    Map<String, Long> synchronizedHashMap;

    @Setup(Level.Iteration)
    public void setUp() {
      table = ConcurrentHashtable.D1.createBounded(D1Entry.class, CAPACITY);
      concurrentHashMap = new ConcurrentHashMap<>(CAPACITY);
      skipListMap = new ConcurrentSkipListMap<>();
      synchronizedHashMap = Collections.synchronizedMap(new HashMap<>(CAPACITY));
      for (int i = 0; i < N_KEYS; ++i) {
        table.tryGetOrCreateOrNull(KEYS[i], D1Entry::new);
        concurrentHashMap.put(KEYS[i], (long) i);
        skipListMap.put(KEYS[i], (long) i);
        synchronizedHashMap.put(KEYS[i], (long) i);
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
  public D1Entry get_concurrentHashtable(SharedState s, ThreadState t) {
    return s.table.get(KEYS[t.next()]);
  }

  @Benchmark
  public Long get_concurrentHashMap(SharedState s, ThreadState t) {
    return s.concurrentHashMap.get(KEYS[t.next()]);
  }

  @Benchmark
  public Long get_concurrentSkipListMap(SharedState s, ThreadState t) {
    return s.skipListMap.get(KEYS[t.next()]);
  }

  @Benchmark
  public Long get_synchronizedHashMap(SharedState s, ThreadState t) {
    return s.synchronizedHashMap.get(KEYS[t.next()]);
  }

  @Benchmark
  public D1Entry getOrCreate_concurrentHashtable(SharedState s, ThreadState t) {
    return s.table.tryGetOrCreateOrNull(KEYS[t.next()], D1Entry::new);
  }

  /**
   * get-first pattern for CHM — the idiomatic equivalent of D1.getOrCreate on a mostly-populated
   * table.
   */
  @Benchmark
  public Long getOrCreate_concurrentHashMap(SharedState s, ThreadState t) {
    String key = KEYS[t.next()];
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
    String key = KEYS[t.next()];
    Long existing = s.synchronizedHashMap.get(key);
    if (existing != null) {
      return existing;
    }
    synchronized (s.synchronizedHashMap) {
      return s.synchronizedHashMap.computeIfAbsent(key, k -> 0L);
    }
  }
}

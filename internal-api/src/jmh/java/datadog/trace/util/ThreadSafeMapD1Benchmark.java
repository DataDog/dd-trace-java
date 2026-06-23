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
 * Compares thread-safe map strategies for shared, concurrent single-key lookups.
 *
 * <p>See {@link ThreadSafeMapD2Benchmark} for the composite-key variant, which adds the cost of
 * hashing two keys and a wrapper object allocation for map-based alternatives.
 *
 * <p>The table is shared across all threads ({@link Scope#Benchmark}) and pre-populated before the
 * measurement iteration — modelling the steady-state read-mostly pattern that the tracer uses (a
 * per-class or per-method instrumentation cache consulted on every invocation).
 *
 * <p>Strategies compared:
 *
 * <ul>
 *   <li>{@link ConcurrentHashtable.D1} — lock-free reads, no extra allocation per lookup.
 *   <li>{@link ConcurrentHashMap} — striped locking; the key is the string itself, no wrapper.
 *   <li>{@link ConcurrentSkipListMap} — fully lock-free (CAS), but pays tree traversal and {@link
 *       Comparable} overhead on every operation.
 *   <li>{@link Collections#synchronizedMap} wrapping {@link HashMap} — global lock on every
 *       operation. Establishes the coarse-locking baseline.
 * </ul>
 *
 * <p><b>Key identity.</b> Lookups reuse the same interned {@code KEYS} instances used to populate
 * the table, so they hit the {@code ==} identity fast path rather than {@code equals()}. This is
 * deliberate and realistic for the tracer, whose map keys are typically interned string literals
 * (tag-name constants); it is <i>not</i> an oversight. ({@code ImmutableMapBenchmark} covers the
 * distinct-instance {@code equals()} path explicitly via its {@code _sameKey} vs default variants.)
 *
 * <p>Java 17 results ({@code @Fork(2)}, {@code @Threads(8)}, 64 pre-populated keys):
 *
 * <pre>{@code
 * Benchmark                             Score   Units
 * get_concurrentHashtable               1583   ops/us  (fastest)
 * get_concurrentHashMap                 1145   ops/us
 * get_concurrentSkipListMap              170   ops/us
 * get_synchronizedHashMap                 33   ops/us
 *
 * getOrCreate_concurrentHashtable       1450   ops/us  (fastest)
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
      table = new ConcurrentHashtable.D1<>(CAPACITY);
      concurrentHashMap = new ConcurrentHashMap<>(CAPACITY);
      skipListMap = new ConcurrentSkipListMap<>();
      synchronizedHashMap = Collections.synchronizedMap(new HashMap<>(CAPACITY));
      for (int i = 0; i < N_KEYS; ++i) {
        table.getOrCreate(KEYS[i], D1Entry::new);
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
    return s.table.getOrCreate(KEYS[t.next()], D1Entry::new);
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

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
 *       K2 is {@link Integer} (boxed), so EA may still eliminate the box on hits, but the
 *       allocation is observable on misses.
 *   <li>{@link ConcurrentHashtable.Support} (custom entry) — same lock-free read path, but K2 is a
 *       primitive {@code int} embedded directly in the entry. No boxing at any point; demonstrates
 *       the flexibility available when {@code D2}'s object-key constraint is too limiting.
 *   <li>{@link ConcurrentHashMap} — striped locking, allocates a {@link Key2} wrapper per lookup
 *       (boxes the {@code int} K2 inside).
 *   <li>{@link ConcurrentSkipListMap} — fully lock-free (CAS), but pays tree traversal and {@link
 *       Comparable} overhead; allocates {@link Key2} per lookup. {@code getOrCreate} uses
 *       get-then-{@code putIfAbsent} (no native {@code computeIfAbsent}).
 *   <li>{@link Collections#synchronizedMap} wrapping {@link HashMap} — global lock on every
 *       operation; allocates {@link Key2} per lookup. Establishes the coarse-locking baseline.
 * </ul>
 *
 * <p><b>Key identity.</b> Lookups reuse the same interned {@code SOURCE_K1} strings and cached
 * {@code SOURCE_K2} Integers used to populate the table, so the key-part comparisons hit the {@code
 * ==} identity fast path rather than {@code equals()}. This is deliberate and realistic for the
 * tracer, whose keys are typically interned literals (tag-name constants) and small boxed ints; it
 * is <i>not</i> an oversight.
 *
 * <p>Java 17 results ({@code @Fork(2)}, {@code @Threads(8)}, 64 pre-populated keys):
 *
 * <pre>{@code
 * Benchmark                              Score   Units
 * get_concurrentHashtable                1452   ops/us  (tied fastest)
 * get_support                            1450   ops/us  (primitive int K2)
 * get_concurrentHashMap                   777   ops/us  (allocates Key2 wrapper)
 * get_concurrentSkipListMap               146   ops/us
 * get_synchronizedHashMap                  27   ops/us
 *
 * getOrCreate_support                    1379   ops/us  (fastest)
 * getOrCreate_concurrentHashtable        1119   ops/us
 * getOrCreate_concurrentHashMap           769   ops/us
 * getOrCreate_concurrentSkipListMap       151   ops/us
 * getOrCreate_synchronizedHashMap          28   ops/us
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>{@code ConcurrentHashtable} and {@code Support} are neck-and-neck on {@code get} (1452 vs
 *       1450 ops/us); both avoid the {@link Key2} wrapper allocation that {@code ConcurrentHashMap}
 *       requires on every lookup.
 *   <li>{@code ConcurrentHashMap} is ~2× slower than {@code ConcurrentHashtable} on {@code get}
 *       (777 vs 1452 ops/us) — the {@link Key2} allocation plus two-level hash lookup adds up.
 *   <li>{@code Support} shows slightly higher {@code getOrCreate} throughput than {@code D2} (1379
 *       vs 1119 ops/us) because its primitive {@code int} K2 field avoids boxing inside the entry
 *       match on the write-path re-check.
 *   <li>{@code ConcurrentSkipListMap} is ~5× slower than {@code ConcurrentHashMap} due to tree
 *       traversal; the two-traversal {@code getOrCreate} pattern adds further overhead on misses.
 *   <li>Synchronized {@code HashMap} is ~50× slower than {@code ConcurrentHashtable}.
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
  static final int[] SOURCE_K2_INT = new int[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      SOURCE_K1[i] = "key-" + i;
      SOURCE_K2_INT[i] = i * 31 + 17;
      SOURCE_K2[i] = SOURCE_K2_INT[i];
    }
  }

  static final class D2Entry extends ConcurrentHashtable.D2.Entry<String, Integer> {
    final long value;

    D2Entry(String k1, Integer k2) {
      super(k1, k2);
      this.value = 1L;
    }
  }

  /**
   * Support-based entry with a primitive {@code int} K2 — no boxing at any point. The hash is
   * computed with the same formula as {@link Hashtable.D2.Entry#hash} but avoids the {@link
   * Integer#hashCode(int)} boxing path by calling {@link LongHashingUtils} directly.
   */
  static final class SupportEntry extends ConcurrentHashtable.Entry {
    final String k1;
    final int k2;
    final long value;

    SupportEntry(String k1, int k2) {
      super(hash(k1, k2));
      this.k1 = k1;
      this.k2 = k2;
      this.value = 1L;
    }

    static long hash(String k1, int k2) {
      return LongHashingUtils.hash(k1.hashCode(), Integer.hashCode(k2));
    }

    boolean matches(String k1, int k2) {
      return this.k2 == k2 && this.k1.equals(k1);
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
    java.util.concurrent.atomic.AtomicReferenceArray<ConcurrentHashtable.Entry> supportBuckets;
    ConcurrentHashMap<Key2, Long> concurrentHashMap;
    ConcurrentSkipListMap<Key2, Long> skipListMap;
    Map<Key2, Long> synchronizedHashMap;

    @Setup(Level.Iteration)
    public void setUp() {
      table = new ConcurrentHashtable.D2<>(CAPACITY);
      supportBuckets =
          new java.util.concurrent.atomic.AtomicReferenceArray<>(
              ConcurrentHashtable.Support.sizeFor(CAPACITY));
      concurrentHashMap = new ConcurrentHashMap<>(CAPACITY);
      skipListMap = new ConcurrentSkipListMap<>();
      synchronizedHashMap = Collections.synchronizedMap(new HashMap<>(CAPACITY));
      for (int i = 0; i < N_KEYS; ++i) {
        int k2 = SOURCE_K2[i];
        table.getOrCreate(SOURCE_K1[i], SOURCE_K2[i], D2Entry::new);
        // populate support table
        SupportEntry se = new SupportEntry(SOURCE_K1[i], k2);
        int idx = ConcurrentHashtable.Support.bucketIndex(supportBuckets, se.keyHash);
        se.setNext(ConcurrentHashtable.Support.bucket(supportBuckets, idx));
        supportBuckets.set(idx, se);
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
  public SupportEntry get_support(SharedState s, ThreadState t) {
    int i = t.next();
    String k1 = SOURCE_K1[i];
    int k2 = SOURCE_K2_INT[i];
    long keyHash = SupportEntry.hash(k1, k2);
    for (SupportEntry e = ConcurrentHashtable.Support.bucket(s.supportBuckets, keyHash);
        e != null;
        e = e.next()) {
      if (e.keyHash == keyHash && e.matches(k1, k2)) {
        return e;
      }
    }
    return null;
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

  @Benchmark
  public SupportEntry getOrCreate_support(SharedState s, ThreadState t) {
    int i = t.next();
    String k1 = SOURCE_K1[i];
    int k2 = SOURCE_K2_INT[i];
    long keyHash = SupportEntry.hash(k1, k2);
    int index = ConcurrentHashtable.Support.bucketIndex(s.supportBuckets, keyHash);
    for (SupportEntry e = ConcurrentHashtable.Support.bucket(s.supportBuckets, index);
        e != null;
        e = e.next()) {
      if (e.keyHash == keyHash && e.matches(k1, k2)) {
        return e;
      }
    }
    synchronized (s.supportBuckets) {
      for (SupportEntry e = ConcurrentHashtable.Support.bucket(s.supportBuckets, index);
          e != null;
          e = e.next()) {
        if (e.keyHash == keyHash && e.matches(k1, k2)) {
          return e;
        }
      }
      SupportEntry newEntry = new SupportEntry(k1, k2);
      newEntry.setNext(ConcurrentHashtable.Support.bucket(s.supportBuckets, index));
      s.supportBuckets.set(index, newEntry);
      return newEntry;
    }
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
   * get-first pattern for ConcurrentSkipListMap — manual get-then-putIfAbsent since CSLM has no
   * computeIfAbsent. Two traversals on miss; one on hit.
   */
  @Benchmark
  public Long getOrCreate_concurrentSkipListMap(SharedState s, ThreadState t) {
    int i = t.next();
    Key2 key = new Key2(SOURCE_K1[i], SOURCE_K2[i]);
    Long existing = s.skipListMap.get(key);
    if (existing != null) {
      return existing;
    }
    Long prev = s.skipListMap.putIfAbsent(key, 0L);
    return prev != null ? prev : 0L;
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

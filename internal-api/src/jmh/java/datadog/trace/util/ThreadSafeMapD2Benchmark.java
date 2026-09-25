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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures steady-state composite-key lookups in a shared, pre-populated table.
 *
 * <p>Compares {@link ConcurrentHashtable.D2}, a custom {@link ConcurrentHashtable.Entry} with a
 * primitive {@code int} key part, {@link ConcurrentHashMap}, {@link ConcurrentSkipListMap}, and a
 * synchronized {@link HashMap}. The table is shared across all threads ({@link Scope#Benchmark})
 * and pre-populated before the measurement iteration — modelling the steady-state read-mostly
 * pattern that the tracer uses (a per-class or per-method instrumentation cache consulted on every
 * invocation).
 *
 * <p>The map cases create a {@link Key2} for each lookup. HotSpot may remove that allocation only
 * when inlining and escape analysis prove that the wrapper is not retained; a miss that inserts the
 * key makes it escape. The concurrent hashtable passes key parts directly, and the custom entry
 * also avoids boxing the {@code int}, so neither optimization depends on escape analysis.
 *
 * <p>Lookups reuse the key-part instances installed during setup, taking the identity fast path for
 * their object comparisons. See {@link ThreadSafeMapD1Benchmark} for single-key lookups.
 *
 * <p>Java 17 results with the front-loaded {@link BenchmarkUtils#warmUpHashDispatch} pollution
 * design ({@code @Fork(2)}, {@code @Threads(8)}, 64 pre-populated keys; ops/us):
 *
 * <pre>{@code
 * Benchmark                               ops/us          B/op
 * get_support                         2730.0 ±  33.4        ~0
 * get_concurrentHashtable             2653.0 ±  81.6        ~0
 * get_concurrentHashMap               1665.0 ±  24.2        ~0
 * get_concurrentSkipListMap            187.3 ±  40.1        ~0
 * get_synchronizedHashMap                9.3 ±   0.4        ~0
 *
 * getOrCreate_support                 2597.4 ± 221.5        ~0
 * getOrCreate_concurrentHashMap       1647.1 ±  75.5        ~0
 * getOrCreate_concurrentHashtable     1397.0 ±  46.5        ~0
 * getOrCreate_concurrentSkipListMap    179.1 ±  40.0        ~0
 * getOrCreate_synchronizedHashMap        9.3 ±   0.4        ~0
 * }</pre>
 *
 * <p><b>The {@link Key2} wrapper is not allocated in the measured code.</b> Every map arm reports
 * ≈0 B/op under {@code -prof gc}, despite the source constructing a {@code Key2} per lookup.
 * LogCompilation confirms the mechanism: C2 emits {@code eliminate_allocation} for {@code Key2},
 * because the never-taken {@code computeIfAbsent} branch is pruned as {@code unstable_if}, which
 * removes the only store of the key and leaves it provably non-escaping.
 *
 * <p>That is a property of this workload, not of the code, and it would not survive in production.
 * Pruning is possible only because {@code @Setup} installs every key before warmup, so the absent
 * branch is never recorded. A real cache records its population-phase misses in the same branch
 * profile — MDO counters accumulate from interpretation onward and are never reset — giving a
 * two-sided profile, no pruning, and a {@code Key2} allocated on every lookup. Treat the {@code
 * ConcurrentHashMap} and {@code ConcurrentSkipListMap} numbers here as an upper bound that a
 * production miss rate would erode. See {@code HashtableD2Benchmark}, where the same wrapper is
 * <i>not</i> eliminated because {@code merge} keeps the present/absent decision inside the callee,
 * leaving no caller-visible branch to prune.
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>{@code Support} and {@code ConcurrentHashtable} are the two fastest on {@code get} (2730.0
 *       and 2653.0 ops/us), ~60% ahead of {@code ConcurrentHashMap} (1665.0). Since the {@code
 *       Key2} allocation is eliminated in all three (see above), that lead is the two-level hash
 *       lookup rather than allocation.
 *   <li>On {@code getOrCreate} the ordering inverts: {@code ConcurrentHashMap} (1647.1) overtakes
 *       {@code ConcurrentHashtable} (1397.0), whose own {@code getOrCreate} is roughly half its
 *       {@code get}. {@code Support} holds up (2597.4). Not root-caused here — the write-path
 *       re-check is the obvious suspect, but it is not measured.
 *   <li>{@code Support} edges {@code D2} on both paths, consistent with its primitive {@code int}
 *       K2 field avoiding boxing inside the entry match on the write-path re-check.
 *   <li>{@code ConcurrentSkipListMap} is ~9× slower than {@code ConcurrentHashMap} due to tree
 *       traversal, though its error bar is wide (±40.1 on a 187.3 mean).
 *   <li>Synchronized {@code HashMap} is roughly 290× slower than the fastest options (9.3 vs 2730.0
 *       ops/us) — lock contention across eight threads on a single monitor, the same magnitude seen
 *       in {@link ThreadSafeMapD1Benchmark}. Type-profile pollution is not a factor: {@code Key2}
 *       is a final class built at the call site, so C2 has an exact type and devirtualizes without
 *       consulting the polluted profile.
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
   * Entry used with the static helpers. Its primitive second key keeps storage and lookup unboxed,
   * independently of {@link Integer} caching or JVM escape analysis.
   */
  static final class SupportEntry extends ConcurrentHashtable.Entry<SupportEntry> {
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

    @Override
    public boolean matches(SupportEntry other) {
      return matches(other.k1, other.k2);
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
      // Varargs-free hash: Objects.hash(k1, k2) would allocate an Object[] per key, penalizing the
      // map baselines with an allocation the wrapper itself doesn't need and overstating the
      // ConcurrentHashtable advantage this benchmark measures.
      this.hash = 31 * k1.hashCode() + k2.hashCode();
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
    java.util.concurrent.atomic.AtomicReferenceArray<SupportEntry> supportBuckets;
    ConcurrentHashMap<Key2, Long> concurrentHashMap;
    ConcurrentSkipListMap<Key2, Long> skipListMap;
    Map<Key2, Long> synchronizedHashMap;

    @Setup(Level.Iteration)
    public void setUp() {
      table = ConcurrentHashtable.D2.createBounded(D2Entry.class, CAPACITY);
      supportBuckets = ConcurrentHashtable.createFixedBuckets(SupportEntry.class, CAPACITY);
      concurrentHashMap = new ConcurrentHashMap<>(CAPACITY);
      skipListMap = new ConcurrentSkipListMap<>();
      synchronizedHashMap = Collections.synchronizedMap(new HashMap<>(CAPACITY));
      for (int i = 0; i < N_KEYS; ++i) {
        int k2 = SOURCE_K2[i];
        table.tryGetOrCreateOrNull(SOURCE_K1[i], SOURCE_K2[i], D2Entry::new);
        // populate support table
        SupportEntry se = new SupportEntry(SOURCE_K1[i], k2);
        synchronized (ConcurrentHashtable.getWriteLock(supportBuckets, se.keyHash)) {
          ConcurrentHashtable.insertHeadEntryFor(supportBuckets, se.keyHash, se);
        }
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

    // Front-load pollution once per trial, entirely before JMH's warmup starts: JMH
    // injects the Blackhole straight into this setup method, so no per-benchmark
    // scratch state is needed.
    @Setup(Level.Trial)
    public void warmUpPollution(Blackhole bh) {
      BenchmarkUtils.warmUpHashDispatch(bh);
    }

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
    for (SupportEntry e = ConcurrentHashtable.bucketFor(s.supportBuckets, keyHash);
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
    return s.table.tryGetOrCreateOrNull(SOURCE_K1[i], SOURCE_K2[i], D2Entry::new);
  }

  @Benchmark
  public SupportEntry getOrCreate_support(SharedState s, ThreadState t) {
    int i = t.next();
    String k1 = SOURCE_K1[i];
    int k2 = SOURCE_K2_INT[i];
    long keyHash = SupportEntry.hash(k1, k2);
    int index = ConcurrentHashtable.bucketIndex(s.supportBuckets, keyHash);
    for (SupportEntry e = ConcurrentHashtable.bucketAt(s.supportBuckets, index);
        e != null;
        e = e.next()) {
      if (e.keyHash == keyHash && e.matches(k1, k2)) {
        return e;
      }
    }
    synchronized (ConcurrentHashtable.getWriteLockAt(s.supportBuckets, index)) {
      for (SupportEntry e = ConcurrentHashtable.bucketAt(s.supportBuckets, index);
          e != null;
          e = e.next()) {
        if (e.keyHash == keyHash && e.matches(k1, k2)) {
          return e;
        }
      }
      SupportEntry newEntry = new SupportEntry(k1, k2);
      ConcurrentHashtable.insertHeadEntryAt(s.supportBuckets, index, newEntry);
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

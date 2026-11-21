package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.util.ThreadSafeMapD2Benchmark.Key2;
import java.util.concurrent.ConcurrentHashMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Demonstrates that {@link BenchmarkUtils#warmUp} closes the branch-profile gap that {@link
 * ThreadSafeMapD2Benchmark}'s javadoc documents but does not fix: with every key pre-installed by
 * {@code @Setup}, {@code getOrCreate}'s absent-key branch never runs, so C2 prunes it as an {@code
 * unstable_if} and scalar-replaces the {@link Key2} allocation that a real, two-sided profile would
 * force onto the heap.
 *
 * <p>The single {@code @Benchmark} method runs the same {@link #getOrCreate} bytecode on an
 * always-hit lookup under both values of {@code pollute}. The only difference is whether {@link
 * ThreadState}'s {@code @Setup(Level.Trial)} first drives that method through a genuine miss, via
 * {@link BenchmarkUtils#warmUp}, on a scratch map. HotSpot records branch counts per bytecode index
 * in the method's profile, not per map instance, so priming on scratch data changes the profile the
 * measured call sees without touching the measured map.
 *
 * <p>Java 17 results (Zulu 17.0.7, MacBook M1, {@code @Fork(2)}, {@code @Threads(8)}, {@code -prof
 * gc}). {@code pollute} is the only thing that differs between the two rows -- same call site, same
 * always-hit measured lookup:
 *
 * <pre>{@code
 * pollute   ops/us           B/op  gc.count
 * false     1610.2 ± 82.4    ≈0      ≈0    <- miss branch pruned (unstable_if), Key2 elided
 * true      1154.7 ± 66.8    24.0    692   <- two-sided profile; Key2 allocated per lookup
 * }</pre>
 *
 * <p>{@code false} reproduces the artificially flattering result that {@link
 * ThreadSafeMapD2Benchmark}'s javadoc documents. {@code true} shows that one {@link
 * BenchmarkUtils#warmUp} call, priming a real miss before measurement, restores the allocation and
 * its ~28% throughput cost, with no change to the measured method or map.
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Threads(8)
public class WarmUpEscapeAnalysisDemoBenchmark {

  static final int N_KEYS = 64;
  static final String[] SOURCE_K1 = new String[N_KEYS];
  static final Integer[] SOURCE_K2 = new Integer[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      SOURCE_K1[i] = "key-" + i;
      SOURCE_K2[i] = i * 31 + 17;
    }
  }

  /** The one call site whose branch profile this benchmark is about; both arms share it. */
  static Long getOrCreate(ConcurrentHashMap<Key2, Long> map, Key2 key) {
    Long existing = map.get(key);
    if (existing != null) {
      return existing;
    }
    return map.computeIfAbsent(key, k -> 0L);
  }

  /** Shared, pre-populated table -- the measured lookups always hit. */
  @State(Scope.Benchmark)
  public static class SharedState {
    ConcurrentHashMap<Key2, Long> map;

    @Setup(Level.Trial)
    public void setUp() {
      map = new ConcurrentHashMap<>();
      for (int i = 0; i < N_KEYS; ++i) {
        // Plain put, deliberately not routed through getOrCreate: this is the condition
        // ThreadSafeMapD2Benchmark's javadoc documents as artificially flattering the benchmark.
        map.put(new Key2(SOURCE_K1[i], SOURCE_K2[i]), (long) i);
      }
    }
  }

  @State(Scope.Thread)
  public static class ThreadState {
    /**
     * {@code false} reproduces the one-sided profile documented in {@link
     * ThreadSafeMapD2Benchmark}; {@code true} primes {@link
     * WarmUpEscapeAnalysisDemoBenchmark#getOrCreate}'s miss branch first.
     */
    @Param({"false", "true"})
    boolean pollute;

    int cursor;
    int missCounter;
    final ConcurrentHashMap<Key2, Long> scratch = new ConcurrentHashMap<>();
    final Key2 scratchHitKey = new Key2("scratch-hit", -1);

    @Setup(Level.Trial)
    public void warmUp(Blackhole bh) {
      if (!pollute) {
        return;
      }
      getOrCreate(scratch, scratchHitKey); // install the one key the hit arm below will always find
      BenchmarkUtils.warmUp(
          this,
          bh,
          BenchmarkUtils.bench((ThreadState t) -> getOrCreate(t.scratch, t.scratchHitKey)),
          BenchmarkUtils.bench((ThreadState t) -> getOrCreate(t.scratch, t.freshMissKey())));
      // Every miss inserted a fresh key; drop them so only the branch profile, not a larger live
      // heap, separates this arm from pollute=false.
      scratch.clear();
    }

    /** A key guaranteed never to have been looked up before, so this always takes the miss path. */
    Key2 freshMissKey() {
      return new Key2("scratch-miss-" + (missCounter++), -2);
    }

    int next() {
      int i = cursor;
      cursor = (i + 1) & (N_KEYS - 1);
      return i;
    }
  }

  @Benchmark
  public Long getOrCreate_concurrentHashMap(SharedState s, ThreadState t) {
    int i = t.next();
    return getOrCreate(s.map, new Key2(SOURCE_K1[i], SOURCE_K2[i]));
  }
}

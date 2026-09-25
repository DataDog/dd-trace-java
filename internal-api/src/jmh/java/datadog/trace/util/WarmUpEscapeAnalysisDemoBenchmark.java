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
 * <p>Both {@code @Benchmark} methods call the exact same {@link #getOrCreate} bytecode on an
 * always-hit lookup; the only difference is whether {@link ThreadState}'s
 * {@code @Setup(Level.Trial)} drives that same method through a genuine miss arm first, via {@link
 * BenchmarkUtils#warmUp}, on a scratch map before measurement starts. The branch's
 * method-data-object lives per call site (bci), not per map instance, so priming it on scratch data
 * pollutes the profile the measured call sees without touching the measured map's contents.
 *
 * <p>Java 8 results ({@code -prof gc}), {@code pollute} is the only thing that differs between the
 * two rows -- same call site, same always-hit measured lookup:
 *
 * <pre>{@code
 * pollute   ops/us    B/op     gc.count
 * false      750.0     ≈0         ≈0     <- unstable_if prunes the miss branch, Key2 scalar-replaced
 * true       600.5     24.0      763     <- two-sided profile, Key2 allocated on every lookup
 * }</pre>
 *
 * <p>{@code false} reproduces the artificially flattering result {@link ThreadSafeMapD2Benchmark}'s
 * javadoc documents. {@code true} shows a single {@link BenchmarkUtils#warmUp} call, priming one
 * real miss arm before measurement, is enough to restore the allocation and the ~20% slower,
 * production-realistic throughput -- with no change to the measured method or the measured map.
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
     * Off arm: reproduces the one-sided profile documented in ThreadSafeMapD2Benchmark. On arm:
     * primes the real getOrCreate branch with a genuine miss before measurement.
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

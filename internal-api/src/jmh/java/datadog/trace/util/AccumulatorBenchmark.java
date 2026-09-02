package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * {@link Accumulator} vs the alternatives it actually displaces: a single {@code LongAdder} (the
 * collision-free baseline it can never beat, only approach), an independent {@code LongAdder} per
 * counter guarded by a per-counter lock (the "just fix it with LongAdder" natural migration target
 * -- {@code longAdderGroup*}), and the {@code ConcurrentHashMap.computeIfAbsent(key, k -> new
 * AtomicLong())} anti-pattern ({@code chmAtomicLongIncrement*}) that {@link Accumulator} exists to
 * avoid. The CHM variant allocates its counter under the bucket's bin lock the first time its one
 * constant key is seen, but since the map is a {@code @State(Scope.Benchmark)} field shared across
 * the whole run, that allocation happens exactly once; every sampled op after it hits the warmed,
 * already-present fast path. So this measures steady-state {@code computeIfAbsent} lookup overhead
 * on an already-populated map, not the one-time allocation-under-lock cost -- still a useful number
 * (a fixed, small key set that's allocated once and hit for the life of the process, as {@code
 * WafMetricCollector}-style CHM counters are, spends nearly all its time in this same warmed path),
 * just not the pathology the name of this benchmark might suggest.
 *
 * <p><b>{@code longAdderGroup*}: is a "just fix it with LongAdder" helper actually cheaper?</b>
 * {@code groupInc}/{@code groupAccumulateAnd} are the natural correct fix using {@code LongAdder}
 * as the payload: one {@code LongAdder} per counter, with a per-counter lock guarding <em>both</em>
 * the increment and the drain (locking only the drain does nothing -- {@code sumThenReset()}'s
 * internal race is against the {@code LongAdder}'s own CAS-based {@code add()}, not against any
 * lock a caller takes). With this benchmark's single counter, that per-counter lock collapses to
 * one lock shared by every thread -- no thread-based distribution at all -- so it loses badly on
 * the write path against {@link Accumulator}'s thread-sharded stripes, especially under contention.
 * This is the realistic production baseline this class was built to replace (see {@code
 * TracerHealthMetrics}'s pre-migration design, one {@code LongAdder} field per counter): <code>
 * AccumulatorBenchmark.accumulatorIncrement_lowContention           avgt    6    0.010 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorIncrement_highContention          avgt    6    0.025 ±  0.039  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement_lowContention        avgt    6    0.012 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement_highContention       avgt    6    1.178 ±  0.134  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset_lowContention  avgt    6    0.104 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset_highContention avgt    6   13.357 ±  1.203  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd_lowContention    avgt    6    0.024 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd_highContention   avgt    6    2.439 ±  0.337  us/op
 * </code> At high contention, {@link Accumulator} beats the realistic {@code longAdderGroup}
 * baseline by nearly 50x on increment (the call that runs on every event), but is itself
 * roughly 5.5x <em>worse</em> than {@code longAdderGroup} on drain under that same high-contention
 * topology (the call that runs once per reporting cycle) -- {@code accumulateAndReset} walks every
 * stripe with a full {@code getAndSet} per counter, so more stripes (sized for core count) means
 * more per-drain work than {@code longAdderGroup}'s one-lock-per-counter {@code sumThenReset}. This
 * is still a clean win once weighted by call-site frequency -- the increment win is ~50x on a call
 * that fires on every event, the drain loss is ~5.5x on a call that fires once per reporting cycle
 * (e.g. a 30s flush tick) -- but the drain-side regression is real, not "slightly worse," and worth
 * knowing before assuming this trade is free in every topology.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 3, time = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(2)
public class AccumulatorBenchmark {

  enum Counter {
    HITS
  }

  private final LongAdder adder = new LongAdder();
  private final Accumulator<Counter> accumulator = Accumulator.of(Counter.values());
  private final ConcurrentHashMap<String, AtomicLong> chm = new ConcurrentHashMap<>();
  private final LongAdder[] longAdderGroup = {new LongAdder()};

  /**
   * The natural "just use LongAdder" fix for the reset hazard: one {@code LongAdder} per counter,
   * with a per-counter lock guarding both the increment and the drain -- external locking around
   * only the drain does nothing, since {@code sumThenReset()}'s internal race is against the {@code
   * LongAdder}'s own CAS-based {@code add()}, not against any lock a caller takes. This is the fair
   * comparison point: it closes the same reset hazard {@link Accumulator} does, but stripes by
   * <em>counter</em> (one lock per enum constant) instead of by <em>thread</em> (one shared table
   * across all counters) -- so N threads hammering the *same* counter contend on one lock
   * regardless of core count, with no thread-bucket distribution at all.
   */
  private static void groupInc(LongAdder[] group, int ordinal) {
    LongAdder counter = group[ordinal];
    synchronized (counter) {
      counter.add(1L);
    }
  }

  private static long[] groupAccumulateAnd(LongAdder[] group) {
    long[] acc = new long[group.length];
    for (int i = 0; i < group.length; i++) {
      LongAdder counter = group[i];
      synchronized (counter) {
        acc[i] = counter.sumThenReset();
      }
    }
    return acc;
  }

  @Benchmark
  @Threads(1)
  public void longAdderIncrement_lowContention() {
    adder.increment();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void longAdderIncrement_highContention() {
    adder.increment();
  }

  @Benchmark
  @Threads(1)
  public void accumulatorIncrement_lowContention() {
    accumulator.inc(Counter.HITS);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorIncrement_highContention() {
    accumulator.inc(Counter.HITS);
  }

  @Benchmark
  @Threads(1)
  public void chmAtomicLongIncrement_lowContention() {
    chm.computeIfAbsent("hits", k -> new AtomicLong()).incrementAndGet();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void chmAtomicLongIncrement_highContention() {
    chm.computeIfAbsent("hits", k -> new AtomicLong()).incrementAndGet();
  }

  @Benchmark
  @Threads(1)
  public void longAdderSumThenReset_lowContention(Blackhole blackhole) {
    adder.increment();
    blackhole.consume(adder.sumThenReset());
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void longAdderSumThenReset_highContention(Blackhole blackhole) {
    adder.increment();
    blackhole.consume(adder.sumThenReset());
  }

  @Benchmark
  @Threads(1)
  public void accumulatorAccumulateAndReset_lowContention(Blackhole blackhole) {
    accumulator.inc(Counter.HITS);
    blackhole.consume(accumulator.accumulateAndReset());
  }

  /**
   * A deliberately pessimistic topology: every thread both writes and drains on every op, so {@code
   * Threads.MAX} threads are all draining concurrently. Real callers don't do this -- see {@code
   * accumulatorMixed-write}/{@code accumulatorMixed-drain} below for the "many writers, one rare
   * drainer" shape this class actually targets. Kept as the worst-case upper bound: no production
   * topology should be more contended on {@link Accumulator#accumulateAndReset} than this.
   */
  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorAccumulateAndReset_highContention(Blackhole blackhole) {
    accumulator.inc(Counter.HITS);
    blackhole.consume(accumulator.accumulateAndReset());
  }

  /**
   * The realistic counterpart to {@code accumulatorAccumulateAndReset_highContention}: many writer
   * threads incrementing, and a single dedicated thread polling {@link
   * Accumulator#accumulateAndReset} -- not every thread doing both on every op. {@code
   * accumulatorMixed-write} measures increment cost while a drain is actively running; {@code
   * accumulatorMixed-drain} measures the drain's own cost under that same live write pressure. The
   * 4:1 writer:drainer ratio is illustrative of "many writers, rare drain," not tuned to a specific
   * core count.
   */
  @Benchmark
  @Group("accumulatorMixed")
  @GroupThreads(4)
  public void accumulatorMixed_write() {
    accumulator.inc(Counter.HITS);
  }

  @Benchmark
  @Group("accumulatorMixed")
  @GroupThreads(1)
  public void accumulatorMixed_drain(Blackhole blackhole) {
    blackhole.consume(accumulator.accumulateAndReset());
  }

  @Benchmark
  @Threads(1)
  public void longAdderGroupIncrement_lowContention() {
    groupInc(longAdderGroup, Counter.HITS.ordinal());
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void longAdderGroupIncrement_highContention() {
    groupInc(longAdderGroup, Counter.HITS.ordinal());
  }

  @Benchmark
  @Threads(1)
  public void longAdderGroupAccumulateAnd_lowContention(Blackhole blackhole) {
    groupInc(longAdderGroup, Counter.HITS.ordinal());
    blackhole.consume(groupAccumulateAnd(longAdderGroup));
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void longAdderGroupAccumulateAnd_highContention(Blackhole blackhole) {
    groupInc(longAdderGroup, Counter.HITS.ordinal());
    blackhole.consume(groupAccumulateAnd(longAdderGroup));
  }
}

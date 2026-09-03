package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * lock a caller takes). The single-counter {@code *_*} benchmarks below collapse that per-counter
 * lock to one lock shared by every thread -- the degenerate worst case for {@code longAdderGroup},
 * with no thread-based distribution at all. The {@code *8_*} benchmarks fix that: each JMH worker
 * thread is pinned to one of 8 counters for its lifetime (see {@link #threadCounterIndex}), so
 * {@code longAdderGroup8}'s threads split into up to 8 groups each contending their own lock --
 * the topology where distributed locking should actually pay off, forcing {@link Accumulator}'s
 * thread-striped design to earn its write-side win rather than facing a single-counter worst case.
 * Fork(5), 15 samples per benchmark: <code>
 * AccumulatorBenchmark.accumulatorAccumulateAndReset_highContention   avgt   15  2.746 ±  0.050  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset_lowContention    avgt   15  0.056 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset8_highContention  avgt   15  6.875 ±  0.422  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset8_lowContention   avgt   15  0.363 ±  0.003  us/op
 * AccumulatorBenchmark.accumulatorIncrement_highContention            avgt   15  0.009 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorIncrement_lowContention             avgt   15  0.007 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorIncrement8_highContention           avgt   15  0.017 ±  0.009  us/op
 * AccumulatorBenchmark.accumulatorIncrement8_lowContention            avgt   15  0.007 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd_highContention     avgt   15  4.770 ±  1.795  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd_lowContention      avgt   15  0.061 ±  0.007  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd8_highContention    avgt   15  6.025 ±  0.712  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd8_lowContention     avgt   15  0.085 ±  0.004  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement_highContention         avgt   15  2.294 ±  0.101  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement_lowContention          avgt   15  0.019 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement8_highContention        avgt   15  0.786 ±  0.078  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement8_lowContention         avgt   15  0.020 ±  0.001  us/op
 * </code> On the write side, {@link Accumulator} beats {@code longAdderGroup} at high contention by
 * ~255x in the degenerate single-shared-lock case and still by ~46x once counters are fairly spread
 * across 8 locks -- a large, reproducible win either way, on the call that runs on every event.
 * On the drain side, the two designs are close and the comparison is noisy under contention for
 * both: at width 1 {@link Accumulator}'s drain (2.746 us/op) is actually <em>faster</em> than {@code
 * longAdderGroup}'s (4.770 ± 1.795 us/op, itself high-variance), and at width 8 it's only ~1.14x
 * slower (6.875 vs 6.025 us/op) -- not the regression an earlier reading of this benchmark
 * suggested. That earlier reading (13.357 us/op at Fork(2)) turned out to be a correlated anomaly
 * across two independent low-sample runs, not a reproducible result; escalating to Fork(5) (15
 * samples) settled it. Net: a large, robust win on the call that fires on every event, and no
 * confirmed cost on the call that fires once per reporting cycle.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 3, time = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(5)
public class AccumulatorBenchmark {

  enum Counter {
    HITS
  }

  /**
   * An 8-constant counterpart to {@link Counter}, used only by the {@code *8_*} benchmarks below.
   * Unlike {@link Counter}, where every thread hits the single {@code HITS} constant (the worst
   * case for {@code longAdderGroup}'s per-counter locking -- one lock shared by every thread,
   * regardless of core count), these benchmarks spread writes across all 8 constants: each JMH
   * worker thread is pinned to one fixed counter for its lifetime (see {@link #threadCounterIndex}),
   * so under high contention, threads split into up to 8 groups each contending on their own lock
   * instead of all threads sharing one. This is the topology where {@code longAdderGroup}'s
   * distributed locking should actually pay off, and where {@link Accumulator}'s thread-striped
   * design has to earn its win on the write side rather than facing a single-counter worst case.
   * {@code accumulateAndReset}/{@code groupAccumulateAnd} also now walk 8 slots per drain instead of
   * 1, sizing the drain cost closer to {@code TracerHealthMetric}'s 54-constant production shape.
   */
  enum Counter8 {
    COUNTER_0,
    COUNTER_1,
    COUNTER_2,
    COUNTER_3,
    COUNTER_4,
    COUNTER_5,
    COUNTER_6,
    COUNTER_7
  }

  private static final Counter8[] COUNTER8_VALUES = Counter8.values();

  private final LongAdder adder = new LongAdder();
  private final Accumulator<Counter> accumulator = Accumulator.of(Counter.values());
  private final Accumulator<Counter8> accumulator8 = Accumulator.of(Counter8.values());
  private final ConcurrentHashMap<String, AtomicLong> chm = new ConcurrentHashMap<>();
  private final LongAdder[] longAdderGroup = {new LongAdder()};
  private final LongAdder[] longAdderGroup8 = {
    new LongAdder(),
    new LongAdder(),
    new LongAdder(),
    new LongAdder(),
    new LongAdder(),
    new LongAdder(),
    new LongAdder(),
    new LongAdder()
  };

  /**
   * Assigns each JMH worker thread a fixed {@code Counter8} index (round-robin over 8) the first
   * time it calls into any {@code *8_*} benchmark, and keeps returning that same index for the
   * thread's lifetime -- so under {@code Threads.MAX}, writes spread across all 8 counters instead
   * of every thread hammering one.
   */
  private final AtomicInteger threadIndexAssigner = new AtomicInteger();

  private final ThreadLocal<Integer> threadCounterIndex =
      ThreadLocal.withInitial(() -> threadIndexAssigner.getAndIncrement() % COUNTER8_VALUES.length);

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

  @Benchmark
  @Threads(1)
  public void accumulatorIncrement8_lowContention() {
    accumulator8.inc(COUNTER8_VALUES[threadCounterIndex.get()]);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorIncrement8_highContention() {
    accumulator8.inc(COUNTER8_VALUES[threadCounterIndex.get()]);
  }

  @Benchmark
  @Threads(1)
  public void accumulatorAccumulateAndReset8_lowContention(Blackhole blackhole) {
    accumulator8.inc(COUNTER8_VALUES[threadCounterIndex.get()]);
    blackhole.consume(accumulator8.accumulateAndReset());
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorAccumulateAndReset8_highContention(Blackhole blackhole) {
    accumulator8.inc(COUNTER8_VALUES[threadCounterIndex.get()]);
    blackhole.consume(accumulator8.accumulateAndReset());
  }

  @Benchmark
  @Threads(1)
  public void longAdderGroupIncrement8_lowContention() {
    groupInc(longAdderGroup8, threadCounterIndex.get());
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void longAdderGroupIncrement8_highContention() {
    groupInc(longAdderGroup8, threadCounterIndex.get());
  }

  @Benchmark
  @Threads(1)
  public void longAdderGroupAccumulateAnd8_lowContention(Blackhole blackhole) {
    groupInc(longAdderGroup8, threadCounterIndex.get());
    blackhole.consume(groupAccumulateAnd(longAdderGroup8));
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void longAdderGroupAccumulateAnd8_highContention(Blackhole blackhole) {
    groupInc(longAdderGroup8, threadCounterIndex.get());
    blackhole.consume(groupAccumulateAnd(longAdderGroup8));
  }
}

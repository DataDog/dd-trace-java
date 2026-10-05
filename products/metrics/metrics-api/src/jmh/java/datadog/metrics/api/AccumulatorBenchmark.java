package datadog.metrics.api;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
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
 * avoid, and an unstriped {@code AtomicLongArray} ({@code atomicLongArray*}) -- one shared array,
 * no per-thread distribution, isolating the cost of striping itself from the cost of correctness
 * (see {@link #arrayAccumulateAndReset}). The CHM variant allocates its counter under the bucket's
 * bin lock the first time its one constant key is seen, but since the map is a
 * {@code @State(Scope.Benchmark)} field shared across the whole run, that allocation happens
 * exactly once; every sampled op after it hits the warmed, already-present fast path. So this
 * measures steady-state {@code computeIfAbsent} lookup overhead on an already-populated map, not
 * the one-time allocation-under-lock cost -- still a useful number (a fixed, small key set that's
 * allocated once and hit for the life of the process, as {@code WafMetricCollector}-style CHM
 * counters are, spends nearly all its time in this same warmed path), just not the pathology the
 * name of this benchmark might suggest.
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
 * {@code longAdderGroup8}'s threads split into up to 8 groups each contending their own lock -- the
 * topology where distributed locking should actually pay off, forcing {@link Accumulator}'s
 * thread-striped design to earn its write-side win rather than facing a single-counter worst case.
 * Fork(5), 15 samples per benchmark, Apple M1 Max, 10 CPUs - macOS/aarch64 - JDK 25 (Zulu): <code>
 * AccumulatorBenchmark.accumulatorAccumulateAndReset_highContention   avgt   15  2.760 ±  0.052  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset_lowContention    avgt   15  0.049 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset8_highContention  avgt   15  6.939 ±  0.303  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAndReset8_lowContention   avgt   15  0.364 ±  0.003  us/op
 * AccumulatorBenchmark.accumulatorIncrement_highContention            avgt   15  0.009 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorIncrement_lowContention             avgt   15  0.007 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorIncrement8_highContention           avgt   15  0.016 ±  0.006  us/op
 * AccumulatorBenchmark.accumulatorIncrement8_lowContention            avgt   15  0.007 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd_highContention     avgt   15  1.703 ±  1.785  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd_lowContention      avgt   15  0.024 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd8_highContention    avgt   15  5.989 ±  0.241  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd8_lowContention     avgt   15  0.074 ±  0.008  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement_highContention         avgt   15  2.775 ±  0.531  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement_lowContention          avgt   15  0.012 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement8_highContention        avgt   15  0.513 ±  0.094  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement8_lowContention         avgt   15  0.012 ±  0.001  us/op
 * </code> On the write side, {@link Accumulator} beats {@code longAdderGroup} at high contention by
 * ~310x in the degenerate single-shared-lock case and still by ~32x once counters are fairly spread
 * across 8 locks -- a large, reproducible win either way, on the call that runs on every event. On
 * the drain side, the two designs remain close and the comparison stays noisy under contention for
 * both: at width 1 {@link Accumulator}'s drain (2.760 us/op) reads slower than {@code
 * longAdderGroup}'s (1.703 ± 1.785 us/op), but that error bar spans {@link Accumulator}'s own
 * result, so the two aren't distinguishable at this sample size; at width 8 it's ~1.16x slower
 * (6.939 vs 5.989 us/op), consistent with the earlier ~1.14x reading. Reproduced on a second,
 * independent Fork(5) run after the stripe-count cap ({@code MAX_STRIPES = 64}) landed: a large,
 * robust win on the call that fires on every event, and no confirmed cost on the call that fires
 * once per reporting cycle.
 *
 * <p><b>{@code longAdderDelta*}: the fair single-counter baseline.</b> {@code longAdderGroup}'s
 * ~310x/~32x win above is real, but it's the cost of buying {@code sumThenReset()}'s no-lost-update
 * guarantee <em>via a lock</em> -- not the cost of a single {@code LongAdder} on its own.
 * Pre-migration {@code TracerHealthMetrics} never took that lock: it kept a single {@code
 * previousCounts}/{@code countIndex}-tracked differ computing {@code sum() - previous} by hand,
 * which is already lock-free and never loses an update, because a missed delta on one {@code sum()}
 * just shows up whole on the next one (see {@link #deltaSumAndReset}). {@code longAdderDelta_*}/
 * {@code longAdderDeltaMixed_*} below reproduce exactly that pattern -- one {@code LongAdder}, one
 * dedicated differ, no lock anywhere -- as the fairest single-counter comparison to {@link
 * Accumulator}: both designs give the same no-lost-update guarantee, just by different means
 * (per-thread striping vs. a single differ's own unsynchronized bookkeeping), so neither pays for a
 * lock the other doesn't need. Fork(5), 15 samples per benchmark, same machine: <code>
 * AccumulatorBenchmark.accumulatorAccumulateAndReset_lowContention  avgt   15  0.050 ±  0.001  us/op
 * AccumulatorBenchmark.accumulatorMixed                             avgt   15  0.158 ±  0.022  us/op
 * AccumulatorBenchmark.accumulatorMixed:accumulatorMixed_drain      avgt   15  0.692 ±  0.077  us/op
 * AccumulatorBenchmark.accumulatorMixed:accumulatorMixed_write      avgt   15  0.024 ±  0.009  us/op
 * AccumulatorBenchmark.longAdderDelta_lowContention                 avgt   15  0.018 ±  0.001  us/op
 * AccumulatorBenchmark.longAdderDeltaMixed                          avgt   15  0.118 ±  0.006  us/op
 * AccumulatorBenchmark.longAdderDeltaMixed:longAdderDeltaMixed_drain avgt  15  0.122 ±  0.012  us/op
 * AccumulatorBenchmark.longAdderDeltaMixed:longAdderDeltaMixed_write avgt  15  0.117 ±  0.006  us/op
 * </code> Here {@link Accumulator} does <em>not</em> win outright. In the single-threaded
 * inc-then-diff-per-call shape, the safe {@code LongAdder} delta is ~2.8x cheaper (0.018 vs 0.050
 * us/op) -- no striping to fan out or fold back in when there's only one thread. In the realistic
 * many-writers/one-drainer topology ({@code accumulatorMixed} vs {@code longAdderDeltaMixed}),
 * {@link Accumulator} wins the write side by ~4.9x (0.024 vs 0.117 us/op, the call on the hot path)
 * but loses the drain side by ~5.7x (0.692 vs 0.122 us/op) and the combined total by ~1.34x (0.158
 * vs 0.118 us/op) -- the striping that makes writes cheap has to be folded back together somewhere,
 * and that fold costs more than one differ's plain subtraction.
 *
 * <p>That's not a mark against {@link Accumulator}: it's the expected shape of a primitive whose
 * value isn't raw single-counter throughput. {@link Accumulator}'s real win shows up one level up,
 * in a from-scratch before/after of an actual migrated caller -- {@code
 * TracerHealthMetricsBenchmark} (see {@code
 * dd-trace-core/src/jmh/java/datadog/trace/core/monitor/}), which replaced ~49 individual {@code
 * LongAdder} fields and their hand-rolled {@code previousCounts}/{@code countIndex} delta tracking
 * with one {@code Accumulator<TracerHealthMetric>}. There, every hot single-counter call is at
 * parity with or faster than the legacy code (0.6-0.7x of legacy on {@code onSend}, the most
 * frequent real call site), and the batch drain -- the actual shape {@link Accumulator} is for,
 * many counters read and reset together once per reporting cycle -- is where the real-code evidence
 * is decisive, not just competitive. The fair single-counter numbers above are worth publishing
 * precisely because they're not a clean win: they show {@link Accumulator} doesn't need to dominate
 * every synthetic one-counter microbenchmark to be the right call once counters are plural and the
 * drain is the thing that matters, which {@code TracerHealthMetricsBenchmark} demonstrates directly
 * on real code rather than a synthetic stand-in.
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
   * worker thread is pinned to one fixed counter for its lifetime (see {@link
   * #threadCounterIndex}), so under high contention, threads split into up to 8 groups each
   * contending on their own lock instead of all threads sharing one. This is the topology where
   * {@code longAdderGroup}'s distributed locking should actually pay off, and where {@link
   * Accumulator}'s thread-striped design has to earn its win on the write side rather than facing a
   * single-counter worst case. {@code accumulateAndReset}/{@code groupAccumulateAnd} also now walk
   * 8 slots per drain instead of 1, sizing the drain cost closer to {@code TracerHealthMetric}'s
   * 54-constant production shape.
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
  private final LongAdder deltaAdder = new LongAdder();
  private long deltaPrevious;
  private final Accumulator<Counter> accumulator = Accumulator.of(Counter.class);
  private final Accumulator<Counter8> accumulator8 = Accumulator.of(Counter8.class);
  private final ConcurrentHashMap<String, AtomicLong> chm = new ConcurrentHashMap<>();
  private final AtomicLongArray atomicLongArray = new AtomicLongArray(1);
  private final AtomicLongArray atomicLongArray8 = new AtomicLongArray(8);
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

  /**
   * The unstriped baseline: a single shared {@code AtomicLongArray}, one slot per counter, with no
   * per-thread distribution at all -- isolates the cost of {@link Accumulator}'s thread-striping
   * itself from the cost of correctness (unlike {@code longAdderGroup}, this has no lock: {@code
   * getAndAdd} and {@code getAndSet} are each already atomic per-slot, so no coordination is needed
   * to give the same "no increment lost across a drain" guarantee).
   */
  private static long[] arrayAccumulateAndReset(AtomicLongArray array) {
    long[] acc = new long[array.length()];
    for (int i = 0; i < array.length(); i++) {
      acc[i] = array.getAndSet(i, 0L);
    }
    return acc;
  }

  /**
   * The safe, lock-free alternative to {@code sumThenReset()} that pre-migration {@code
   * TracerHealthMetrics} actually used (via {@code previousCounts}/{@code countIndex}): never reset
   * the {@code LongAdder} at all, and have a single differ thread track the last observed {@code
   * sum()} to compute its own delta. {@code sum()} alone never loses an update permanently -- a
   * miss just shows up in the next {@code sum()} -- so this closes {@code sumThenReset()}'s reset
   * race without any lock, at the cost of one extra subtraction per drain. The catch is the "single
   * differ" part: {@code deltaPrevious} is unsynchronized plain state, correct only because exactly
   * one thread ever calls this method between increments. Unlike {@code sumThenReset()}, which
   * degrades gracefully (just an occasional dropped delta) if called from multiple threads at once,
   * concurrent callers here would race on {@code deltaPrevious} itself and corrupt it -- so there
   * is deliberately no {@code longAdderDelta_highContention} mirroring {@code
   * longAdderSumThenReset_highContention}'s "every thread both writes and drains" shape; see {@code
   * longAdderDeltaMixed_write}/{@code _drain} below for the one topology (many writers, one
   * dedicated drainer) this baseline is actually valid under.
   */
  private long deltaSumAndReset() {
    long current = deltaAdder.sum();
    long delta = current - deltaPrevious;
    deltaPrevious = current;
    return delta;
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
  public void longAdderDelta_lowContention(Blackhole blackhole) {
    deltaAdder.increment();
    blackhole.consume(deltaSumAndReset());
  }

  /**
   * The realistic, valid topology for {@link #deltaSumAndReset} -- many writers, one dedicated
   * differ -- mirroring {@code accumulatorMixed_write}/{@code _drain} below so the two can be
   * compared directly: this is the fairest single-counter match for {@link Accumulator}, since both
   * are lock-free on the write side and both give the same no-lost-update guarantee, just by
   * different means (per-slot atomic {@code getAndSet} vs. a single differ's own bookkeeping).
   */
  @Benchmark
  @Group("longAdderDeltaMixed")
  @GroupThreads(4)
  public void longAdderDeltaMixed_write() {
    deltaAdder.increment();
  }

  @Benchmark
  @Group("longAdderDeltaMixed")
  @GroupThreads(1)
  public void longAdderDeltaMixed_drain(Blackhole blackhole) {
    blackhole.consume(deltaSumAndReset());
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
  public void atomicLongArrayIncrement_lowContention() {
    atomicLongArray.getAndAdd(Counter.HITS.ordinal(), 1L);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void atomicLongArrayIncrement_highContention() {
    atomicLongArray.getAndAdd(Counter.HITS.ordinal(), 1L);
  }

  @Benchmark
  @Threads(1)
  public void atomicLongArrayAccumulateAndReset_lowContention(Blackhole blackhole) {
    atomicLongArray.getAndAdd(Counter.HITS.ordinal(), 1L);
    blackhole.consume(arrayAccumulateAndReset(atomicLongArray));
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void atomicLongArrayAccumulateAndReset_highContention(Blackhole blackhole) {
    atomicLongArray.getAndAdd(Counter.HITS.ordinal(), 1L);
    blackhole.consume(arrayAccumulateAndReset(atomicLongArray));
  }

  @Benchmark
  @Threads(1)
  public void atomicLongArrayIncrement8_lowContention() {
    atomicLongArray8.getAndAdd(threadCounterIndex.get(), 1L);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void atomicLongArrayIncrement8_highContention() {
    atomicLongArray8.getAndAdd(threadCounterIndex.get(), 1L);
  }

  @Benchmark
  @Threads(1)
  public void atomicLongArrayAccumulateAndReset8_lowContention(Blackhole blackhole) {
    atomicLongArray8.getAndAdd(threadCounterIndex.get(), 1L);
    blackhole.consume(arrayAccumulateAndReset(atomicLongArray8));
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void atomicLongArrayAccumulateAndReset8_highContention(Blackhole blackhole) {
    atomicLongArray8.getAndAdd(threadCounterIndex.get(), 1L);
    blackhole.consume(arrayAccumulateAndReset(atomicLongArray8));
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

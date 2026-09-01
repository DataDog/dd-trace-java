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
 * {@link Accumulator} vs {@link LongAdder} vs the {@code ConcurrentHashMap.computeIfAbsent(key, k
 * -> new AtomicLong())} anti-pattern, at one thread (no contention) and at {@link Threads#MAX}
 * (heavy contention). The CHM variant allocates its counter under the bucket's bin lock the first
 * time its one constant key is seen -- exactly the pathology {@link Accumulator} exists to avoid --
 * but since the map is a {@code @State(Scope.Benchmark)} field shared across the whole run, that
 * allocation happens exactly once; every sampled op after it hits the warmed, already-present fast
 * path. So this measures steady-state {@code computeIfAbsent} lookup overhead on an
 * already-populated map, not the one-time allocation-under-lock cost -- still a useful number (a
 * fixed, small key set that's allocated once and hit for the life of the process, as {@code
 * WafMetricCollector}-style CHM counters are, spends nearly all its time in this same warmed path),
 * just not the pathology the name of this benchmark might suggest.
 *
 * <p><b>Contention result to note:</b> at low contention, {@code accumulatorIncrement} is
 * essentially free and on par with {@code longAdderIncrement}. At {@code Threads.MAX} (10 threads
 * on the measurement machine), oversizing {@link Accumulator}'s stripe count from 8 (one per core)
 * to 16 (roughly 2x cores, see {@code stripeCount()}) cut {@code
 * accumulatorIncrement_highContention} from ~0.097 us/op to ~0.040 us/op -- fewer threads collide
 * on a stripe, so fewer of them pay {@code synchronized}'s blocking wait instead of a cheap
 * fast-path lock. It is still roughly 4-5x slower than {@code longAdderIncrement} (a collision-free
 * CAS retry beats even an uncontended monitor enter/exit), and {@code accumulateAndReset} under
 * concurrent writers got correspondingly more expensive (~7.5us to ~15.5us) since draining now
 * walks twice as many stripes while writers are actively landing on them. Read {@code
 * accumulatorIncrement_highContention} not as "Accumulator beats LongAdder under contention" (it
 * doesn't, on this shape) but as the honest cost of the drain-under-lock design that buys atomic
 * combine+reset; a caller trading that safety for raw increment throughput should measure their own
 * contention level before choosing between them. <code>
 * Apple M1 Max, 10 CPUs - JDK 1.8.0_382 (Zulu) - macOS/arm64 - stripeCount() = 16
 * Benchmark                                                     Mode  Cnt    Score    Error  Units
 * AccumulatorBenchmark.longAdderIncrement_lowContention         avgt    6    0.007 ±  0.001   us/op
 * AccumulatorBenchmark.longAdderIncrement_highContention        avgt    6    0.009 ±  0.001   us/op
 * AccumulatorBenchmark.accumulatorIncrement_lowContention       avgt    6    0.010 ±  0.001   us/op
 * AccumulatorBenchmark.accumulatorIncrement_highContention      avgt    6    0.040 ±  0.002   us/op
 * AccumulatorBenchmark.chmAtomicLongIncrement_lowContention     avgt    6    0.010 ±  0.001   us/op
 * AccumulatorBenchmark.chmAtomicLongIncrement_highContention    avgt    6    0.417 ±  0.543   us/op
 * AccumulatorBenchmark.longAdderSumThenReset_lowContention      avgt    6    0.012 ±  0.001   us/op
 * AccumulatorBenchmark.longAdderSumThenReset_highContention     avgt    6    2.433 ±  0.203   us/op
 * AccumulatorBenchmark.accumulatorAccumulateAnd_lowContention   avgt    6    0.162 ±  0.009   us/op
 * AccumulatorBenchmark.accumulatorAccumulateAnd_highContention  avgt    6   15.515 ±  4.094   us/op
 * </code>
 *
 * <p>(This run had some background noise from another session on the measurement machine; the
 * {@code lowContention} rows and the {@code highContention} directional deltas are reliable, but
 * treat the exact {@code highContention} magnitudes as approximate.)
 *
 * <p><b>{@code longAdderGroup*}: is a "just fix it with LongAdder" helper actually cheaper?</b>
 * {@code groupInc}/{@code groupAccumulateAnd} are the natural correct fix using {@code LongAdder}
 * as the payload: one {@code LongAdder} per counter, with a per-counter lock guarding <em>both</em>
 * the increment and the drain (locking only the drain does nothing -- {@code sumThenReset()}'s
 * internal race is against the {@code LongAdder}'s own CAS-based {@code add()}, not against any
 * lock a caller takes). This closes the same reset hazard as {@link Accumulator}, but stripes by
 * <em>counter</em> instead of by <em>thread</em>. <code>
 * AccumulatorBenchmark.accumulatorIncrement_highContention         avgt    6   0.029 ±  0.051  us/op
 * AccumulatorBenchmark.accumulatorAccumulateAnd_highContention     avgt    6  13.431 ±  5.876  us/op
 * AccumulatorBenchmark.longAdderGroupIncrement_highContention      avgt    6   0.294 ±  0.088  us/op
 * AccumulatorBenchmark.longAdderGroupAccumulateAnd_highContention  avgt    6   0.549 ±  0.291  us/op
 * </code> Not "similar cost" -- a clean trade-off inversion. With this benchmark's single counter,
 * {@code longAdderGroup}'s per-counter lock collapses to one lock for every thread (no thread-based
 * distribution at all), so it loses badly on the write path: ~10x worse than {@code Accumulator}'s
 * thread-sharded stripes. But its drain only has that one lock to acquire, so it wins big there:
 * ~24x better than {@code Accumulator}, which always walks all 16 stripes on every drain regardless
 * of counter count. That asymmetry is the whole story: {@code longAdderGroup}'s drain cost scales
 * with <em>number of counters</em> (more counters -&gt; more locks to drain), while {@code
 * Accumulator}'s drain cost is fixed at stripe count, independent of counter count. Which design
 * actually wins for a given caller depends on that caller's counter cardinality and whether its
 * write traffic concentrates on a few hot counters (favors thread-sharding) or spreads across many
 * (favors counter-sharding) -- not measured here, and worth checking against the real migration
 * targets before treating either number as the general answer.
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
  private final long[][] accumulator = Accumulator.EmbeddingSupport.create(Counter.values());
  private final ConcurrentHashMap<String, AtomicLong> chm = new ConcurrentHashMap<>();
  private final LongAdder[] longAdderGroup = {new LongAdder()};

  /**
   * The natural "just use LongAdder" fix for the reset hazard: one {@code LongAdder} per counter,
   * with a per-counter lock guarding both the increment and the drain -- external locking around
   * only the drain does nothing, since {@code sumThenReset()}'s internal race is against the {@code
   * LongAdder}'s own CAS-based {@code add()}, not against any lock a caller takes. This is the fair
   * comparison point: it closes the same hazard {@link Accumulator} does, but stripes by
   * <em>counter</em> (one lock per enum constant) instead of by <em>thread</em> (one lock per
   * stripe, shared by all counters) -- so N threads hammering the *same* counter contend on one
   * lock regardless of core count, with no thread-bucket distribution at all.
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
    Accumulator.EmbeddingSupport.inc(accumulator, Counter.HITS);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorIncrement_highContention() {
    Accumulator.EmbeddingSupport.inc(accumulator, Counter.HITS);
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
  public void accumulatorAccumulateAnd_lowContention(Blackhole blackhole) {
    Accumulator.EmbeddingSupport.inc(accumulator, Counter.HITS);
    blackhole.consume(Accumulator.EmbeddingSupport.accumulateAndReset(accumulator));
  }

  /**
   * A deliberately pessimistic topology: every thread both writes and drains on every op, so {@code
   * Threads.MAX} threads are all draining concurrently. Real callers don't do this -- see {@code
   * accumulatorMixed-write}/{@code accumulatorMixed-drain} below for the "many writers, one rare
   * drainer" shape this class actually targets. Kept as the worst-case upper bound: no production
   * topology should be more contended on {@link Accumulator.EmbeddingSupport#accumulateAndReset}
   * than this.
   */
  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorAccumulateAnd_highContention(Blackhole blackhole) {
    Accumulator.EmbeddingSupport.inc(accumulator, Counter.HITS);
    blackhole.consume(Accumulator.EmbeddingSupport.accumulateAndReset(accumulator));
  }

  /**
   * The realistic counterpart to {@code accumulatorAccumulateAnd_highContention}: many writer
   * threads incrementing, and a single dedicated thread polling {@link
   * Accumulator.EmbeddingSupport#accumulateAndReset} -- not every thread doing both on every op.
   * {@code accumulatorMixed-write} measures increment cost while a drain is actively contending for
   * stripe locks; {@code accumulatorMixed-drain} measures the drain's own cost under that same live
   * write pressure. The 4:1 writer:drainer ratio is illustrative of "many writers, rare drain," not
   * tuned to a specific core count.
   */
  @Benchmark
  @Group("accumulatorMixed")
  @GroupThreads(4)
  public void accumulatorMixed_write() {
    Accumulator.EmbeddingSupport.inc(accumulator, Counter.HITS);
  }

  @Benchmark
  @Group("accumulatorMixed")
  @GroupThreads(1)
  public void accumulatorMixed_drain(Blackhole blackhole) {
    blackhole.consume(Accumulator.EmbeddingSupport.accumulateAndReset(accumulator));
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

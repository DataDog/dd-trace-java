package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * (heavy contention). The CHM variant allocates its counter under the bucket's bin lock on first
 * sight of a key -- exactly the pathology {@link Accumulator} exists to avoid -- so its comparison
 * here is against that allocation-under-lock step, not against a pre-warmed map.
 *
 * <p><b>Contention result to note:</b> at low contention, {@code accumulatorIncrement} is
 * essentially free and on par with {@code longAdderIncrement}. At {@code Threads.MAX} (10 threads
 * on the measurement machine), oversizing {@link Accumulator}'s stripe count from 8 (one per core)
 * to 16 (roughly 2x cores, see {@code stripeCount()}) cut {@code
 * accumulatorIncrement_highContention} from ~0.097 us/op to ~0.040 us/op -- fewer threads collide
 * on a stripe, so fewer of them pay {@code synchronized}'s blocking wait instead of a cheap
 * fast-path lock. It is still roughly 4-5x slower than {@code longAdderIncrement} (a collision-free
 * CAS retry beats even an uncontended monitor enter/exit), and {@code accumulateAnd} under
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
  private final long[][] accumulator = Accumulator.create(Counter.values());
  private final ConcurrentHashMap<String, AtomicLong> chm = new ConcurrentHashMap<>();

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
    Accumulator.inc(accumulator, Counter.HITS);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorIncrement_highContention() {
    Accumulator.inc(accumulator, Counter.HITS);
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
    Accumulator.inc(accumulator, Counter.HITS);
    blackhole.consume(Accumulator.accumulateAnd(accumulator));
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorAccumulateAnd_highContention(Blackhole blackhole) {
    Accumulator.inc(accumulator, Counter.HITS);
    blackhole.consume(Accumulator.accumulateAnd(accumulator));
  }
}

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

package datadog.trace.common;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.concurrent.atomic.LongAdder;
import org.jctools.counters.CountersFactory;
import org.jctools.counters.FixedSizeStripedLongCounter;
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

/*
 * Benchmark Mode Cnt Score Error Units
 * LongAdderBenchmark.benchLongAdderIncrement avgt 0.009 us/op
 * LongAdderBenchmark.benchLongAdderSum avgt 0.297 us/op
 * LongAdderBenchmark.benchStripedCounterIncrement avgt 0.071 us/op
 * LongAdderBenchmark.benchStripedCounterSum avgt 0.425 us/op
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 1, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressForbidden
public class LongAdderBenchmark {
  public final LongAdder adder = new LongAdder();
  public final FixedSizeStripedLongCounter counter =
      CountersFactory.createFixedSizeStripedCounter(8);

  @Benchmark
  @Threads(Threads.MAX)
  public void benchLongAdderIncrement(Blackhole blackhole) {
    adder.increment();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void benchStripedCounterIncrement(Blackhole blackhole) {
    counter.inc();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void benchLongAdderSum(Blackhole blackhole) {
    adder.increment();
    blackhole.consume(adder.sum());
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void benchStripedCounterSum(Blackhole blackhole) {
    counter.inc();
    blackhole.consume(counter.get());
  }
}

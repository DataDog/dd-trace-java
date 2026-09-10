package datadog.metrics.api;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.metrics.api.statsd.StatsDClient;
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

/**
 * A decision-support benchmark, not a design-proof one: {@link AccumulatorBenchmark} exists to
 * justify {@link Accumulator}'s internal striping design against synthetic alternatives ({@code
 * LongAdder}, a CHM of {@code AtomicLong}, an unstriped {@code AtomicLongArray}) that nobody in
 * this codebase would actually reach for instead -- that question is settled and doesn't need
 * re-litigating on every read. This class answers a different, durable one: given both {@link
 * Counter} and {@link Accumulator} live in {@code metrics-api}, which do you actually use?
 *
 * <p>{@link Counter} is the one most callers reach for first, and for good reason -- it's the
 * advertised, general-purpose metrics API. But its real implementation ({@code StatsDCounter} in
 * {@code metrics-lib}) calls {@link StatsDClient#count} synchronously on every {@link
 * Counter#increment}, with no batching or striping of its own. {@link #counterIncrement} below
 * mirrors that shape exactly (forwarding straight to a no-op {@link StatsDClient} to isolate the
 * call-site cost from real network I/O, which would swamp everything else and isn't the question
 * this asks -- {@code StatsDCounter} itself has package-private construction, so this reimplements
 * its shape rather than depending on {@code metrics-lib}). {@link Accumulator} exists because that
 * per-call cost is too high to pay on every request/span/event -- it stripes by thread and defers
 * reporting to a periodic drain instead.
 *
 * <p><b>Rule of thumb:</b> a counter incremented on a hot path (every request, span, or event)
 * should use {@link Accumulator}, drained on a reporting cadence. A counter incremented rarely
 * (startup, config changes, an error path already off the hot path) can use {@link Counter}
 * directly, no ceremony required. This is the concrete case behind the (not yet built)
 * {@code @ForegroundSafe}/{@code @BackgroundOnly} annotations: {@link Counter#increment} is
 * {@code @BackgroundOnly}, {@link Accumulator#inc} is {@code @ForegroundSafe}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 3, time = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(5)
public class AccumulatorVsCounterBenchmark {

  enum Metric {
    HITS
  }

  private final Accumulator<Metric> accumulator = Accumulator.of(Metric.class);
  private final Counter counter = new SynchronousStatsDCounter("hits", StatsDClient.NO_OP);

  /**
   * Mirrors {@code StatsDCounter}'s real shape: every {@link #increment} forwards straight to the
   * client, with no batching of its own. Reimplemented here rather than depending on {@code
   * metrics-lib} because {@code StatsDCounter}'s constructor is package-private.
   */
  private static final class SynchronousStatsDCounter implements Counter {
    private static final String[] NO_TAGS = new String[0];
    private final String name;
    private final StatsDClient statsd;

    SynchronousStatsDCounter(String name, StatsDClient statsd) {
      this.name = name;
      this.statsd = statsd;
    }

    @Override
    public void increment(int delta) {
      statsd.count(name, delta, NO_TAGS);
    }

    @Override
    public void incrementErrorCount(String cause, int delta) {
      statsd.count(name, delta, new String[] {"cause:" + cause});
    }
  }

  @Benchmark
  @Threads(1)
  public void accumulatorIncrement_lowContention() {
    accumulator.inc(Metric.HITS);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void accumulatorIncrement_highContention() {
    accumulator.inc(Metric.HITS);
  }

  @Benchmark
  @Threads(1)
  public void counterIncrement_lowContention() {
    counter.increment(1);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void counterIncrement_highContention() {
    counter.increment(1);
  }
}

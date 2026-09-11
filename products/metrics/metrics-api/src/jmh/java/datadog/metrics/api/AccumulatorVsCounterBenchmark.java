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
 * Counter#increment}, with no batching or striping of its own, and the real {@code StatsDClient}
 * underneath serializes that call through one shared connection (a lock or an offer to a single
 * queue) -- the same "one shared serialization point, no thread distribution" shape {@link
 * AccumulatorBenchmark}'s {@code longAdderGroup} single-lock variants model. {@link
 * #counterIncrement} below mirrors that shape with a {@link StatsDClient} that takes a real lock on
 * every call (see {@link LockingStatsDClient}) rather than a true no-op -- a true no-op measures
 * only virtual-dispatch overhead and understates {@code StatsDCounter}'s actual per-call cost to
 * the point of not answering this benchmark's own question ({@code StatsDCounter} itself has
 * package-private construction, so this reimplements its shape rather than depending on {@code
 * metrics-lib}). {@link Accumulator} exists because that per-call cost is too high to pay on every
 * request/span/event -- it stripes by thread and defers reporting to a periodic drain instead.
 *
 * <p><b>Rule of thumb:</b> a counter incremented on a hot path (every request, span, or event)
 * should use {@link Accumulator}, drained on a reporting cadence. A counter incremented rarely
 * (startup, config changes, an error path already off the hot path) can use {@link Counter}
 * directly, no ceremony required. This is the concrete case behind the (not yet built)
 * {@code @ForegroundSafe}/{@code @BackgroundOnly} annotations: {@link Counter#increment} is
 * {@code @BackgroundOnly}, {@link Accumulator#inc} is {@code @ForegroundSafe}.
 *
 * <p>Fork(5), 15 samples per benchmark, Apple M1 Max, 10 CPUs - macOS/aarch64 - JDK 25 (Zulu):
 * <code>
 * AccumulatorVsCounterBenchmark.accumulatorIncrement_highContention  avgt   15  0.009 ±  0.001  us/op
 * AccumulatorVsCounterBenchmark.accumulatorIncrement_lowContention   avgt   15  0.007 ±  0.001  us/op
 * AccumulatorVsCounterBenchmark.counterIncrement_highContention      avgt   15  1.559 ±  0.941  us/op
 * AccumulatorVsCounterBenchmark.counterIncrement_lowContention       avgt   15  0.009 ±  0.001  us/op
 * </code> At low contention the two are indistinguishable (0.007 vs 0.009 us/op) -- an uncontended
 * lock costs almost nothing, so with only one thread ever calling in, {@link Counter}'s per-call
 * cost and {@link Accumulator}'s are both dominated by the same handful of instructions. At high
 * contention {@link Accumulator} wins by ~173x (0.009 vs 1.559 us/op, itself high-variance from run
 * to run) -- {@link Counter}'s one shared lock serializes every calling thread, while {@link
 * Accumulator}'s per-thread striping doesn't. This is the expected result for a counter hit from
 * many concurrent threads, and it's why the Rule of thumb above exists.
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
  private final Counter counter = new SynchronousStatsDCounter("hits", new LockingStatsDClient());

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

  /**
   * A {@link StatsDClient} stand-in that pays a real, serialized per-call cost instead of a true
   * no-op: every real {@code StatsDClient} forwards {@code count} through one shared connection (a
   * lock or an offer to a single non-blocking queue), so a true no-op would measure only
   * virtual-dispatch overhead and understate the cost this benchmark exists to isolate. A {@code
   * synchronized} increment of a shared counter is a reasonable stand-in for that shared
   * serialization point without pulling in a real socket/queue implementation this benchmark
   * doesn't need.
   */
  private static final class LockingStatsDClient implements StatsDClient {
    private final Object lock = new Object();
    private long total;

    @Override
    public void incrementCounter(String metricName, String... tags) {
      count(metricName, 1L, tags);
    }

    @Override
    public void count(String metricName, long delta, String... tags) {
      synchronized (lock) {
        total += delta;
      }
    }

    @Override
    public void gauge(String metricName, long value, String... tags) {}

    @Override
    public void gauge(String metricName, double value, String... tags) {}

    @Override
    public void histogram(String metricName, long value, String... tags) {}

    @Override
    public void histogram(String metricName, double value, String... tags) {}

    @Override
    public void distribution(String metricName, long value, String... tags) {}

    @Override
    public void distribution(String metricName, double value, String... tags) {}

    @Override
    public void serviceCheck(
        String serviceCheckName, String status, String message, String... tags) {}

    @Override
    public void error(Exception error) {}

    @Override
    public int getErrorCount() {
      return 0;
    }

    @Override
    public void close() {}
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

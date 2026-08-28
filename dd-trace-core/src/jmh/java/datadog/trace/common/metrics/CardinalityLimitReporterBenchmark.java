package datadog.trace.common.metrics;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Steady-state {@code record()} acceptance check: once every tag in the working set has an entry,
 * every call should be a lookup + in-place count bump through the {@link
 * datadog.trace.util.Hashtable.D1#tryGetOrCreate} {@code Maybe}, with no per-call allocation. Run
 * with {@code -prof gc} -- B/op should read ~0.
 *
 * <p>Not thread-safe by design (see {@link CardinalityLimitReporter}'s class javadoc), so each
 * thread gets its own reporter and tag pool rather than sharing one instance.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@Threads(8)
public class CardinalityLimitReporterBenchmark {

  private static final int DISTINCT_TAGS = 32;

  private CardinalityLimitReporter reporter;
  private String[] tags;
  private int cursor;

  @Setup(Level.Trial)
  public void setup() {
    this.reporter = new CardinalityLimitReporter();
    this.tags = new String[DISTINCT_TAGS];
    for (int i = 0; i < DISTINCT_TAGS; i++) {
      tags[i] = "tag-" + i;
    }
    // Pre-populate every entry so the measured path is pure lookup + update, not creation.
    for (String tag : tags) {
      reporter.record(tag, 1);
    }
  }

  @Benchmark
  public void record() {
    String tag = tags[cursor++ & (DISTINCT_TAGS - 1)];
    long count = 1L + (ThreadLocalRandom.current().nextLong() & 0xFF);
    reporter.record(tag, count);
  }
}

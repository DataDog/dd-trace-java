package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.WellKnownTags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Adversarial JMH benchmark designed to stress the metrics subsystem's capacity bounds.
 *
 * <p>The metrics aggregator is bounded at every layer:
 *
 * <ul>
 *   <li>The aggregate cache caps total entries at {@code tracerMetricsMaxAggregates} (default
 *       2048). Beyond that LRU eviction kicks in.
 *   <li>The producer/consumer inbox is a fixed-size MPSC queue ({@code tracerMetricsMaxPending});
 *       when full, producer {@code offer} returns false and the snapshot is dropped via {@link
 *       HealthMetrics#onStatsInboxFull()}.
 *   <li>Histograms use a bounded dense store -- per-histogram memory is fixed.
 * </ul>
 *
 * <p>The benchmark hammers all of these simultaneously with 8 producer threads, unique labels per
 * op (so the aggregate cache fills+evicts repeatedly), random durations across a wide range (so
 * histograms accept many distinct bins), and random {@code error}/{@code topLevel} flags (so both
 * histograms are exercised). After the run, drop counters are printed so you can see how the
 * subsystem absorbed the burst.
 *
 * <p>What "OOM the metrics subsystem" would look like if the bounds break: producer-thread
 * allocation would grow unbounded (snapshots faster than the inbox can drain produces dropped
 * snapshots, not heap growth); aggregator-thread heap would grow if entries weren't capped or
 * histograms grew past their dense-store limit.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Threads(8)
@Fork(value = 1)
public class AdversarialMetricsBenchmark {

  private ClientStatsAggregator aggregator;
  private CountingHealthMetrics health;

  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;
  }

  @Setup
  public void setup() {
    this.health = new CountingHealthMetrics();
    this.aggregator =
        new ClientStatsAggregator(
            new WellKnownTags("", "", "", "", "", ""),
            Collections.emptySet(),
            AdditionalTagsSchema.EMPTY,
            new ClientStatsAggregatorBenchmark.FixedAgentFeaturesDiscovery(
                Collections.singleton("peer.hostname"), Collections.emptySet()),
            this.health,
            new ClientStatsAggregatorBenchmark.NullSink(),
            2048,
            2048,
            false);
    this.aggregator.start();
  }

  @TearDown
  @SuppressForbidden
  public void tearDown() {
    aggregator.close();
    // Counters accumulate across the trial (warmup + measurement iterations), since the
    // CountingHealthMetrics instance is created once in @Setup and never reset.
    System.err.println(
        "[ADVERSARIAL] drops over the trial (8 threads, warmup + measurement combined):");
    System.err.println(
        "  onStatsInboxFull         = "
            + health.inboxFull.sum()
            + "   (snapshots dropped because the MPSC inbox was full)");
    System.err.println(
        "  onStatsAggregateDropped  = "
            + health.aggregateDropped.sum()
            + "   (snapshots dropped because the aggregate cache was full with no stale entry)");
  }

  @Benchmark
  public void publish(ThreadState ts, Blackhole blackhole) {
    int idx = ts.cursor++;
    ThreadLocalRandom rng = ThreadLocalRandom.current();

    // Mix indices so labels don't fall into linear order. Distinct labels exceed every reasonable
    // working-set bound, so the aggregate cache evicts continuously and most ops force a fresh
    // MetricKey construction on the consumer thread.
    int scrambled = idx * 0x9E3779B1; // golden ratio multiplier
    String service = "svc-" + (scrambled & 0xFFFF);
    String operation = "op-" + ((scrambled >>> 8) & 0x3FFFF);
    String resource = "res-" + ((scrambled ^ 0x5A5A5A) & 0xFFFFF);
    String hostname = "host-" + ((scrambled >>> 12) & 0x7FFF);
    boolean error = (idx & 7) == 0;
    boolean topLevel = (idx & 3) == 0;
    // Wide duration spread forces histogram bins to populate broadly.
    long durationNanos = 1L + (rng.nextLong() & 0x3FFFFFFFL); // 1 ns .. ~1.07 s

    SimpleSpan span =
        new SimpleSpan(
            service, operation, resource, "web", true, topLevel, error, 0, durationNanos, 200);
    span.setTag(SPAN_KIND, SPAN_KIND_CLIENT);
    span.setTag("peer.hostname", hostname);

    List<CoreSpan<?>> trace = Collections.singletonList(span);
    blackhole.consume(aggregator.publish(trace));
  }

  /**
   * Counts what gets dropped. Uses {@link LongAdder} so the printed totals hold up under 8-way
   * contention -- {@code volatile long ++} loses ~20% of updates here, which would mask the
   * order-of-magnitude shape the bench is trying to surface (inbox-full vs aggregate-dropped).
   */
  static final class CountingHealthMetrics extends HealthMetrics {
    final LongAdder inboxFull = new LongAdder();
    final LongAdder aggregateDropped = new LongAdder();

    @Override
    public void onStatsInboxFull() {
      inboxFull.increment();
    }

    @Override
    public void onStatsAggregateDropped() {
      aggregateDropped.increment();
    }
  }
}

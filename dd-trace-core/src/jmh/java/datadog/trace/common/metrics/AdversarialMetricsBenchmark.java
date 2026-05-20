package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.WellKnownTags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
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
 * Adversarial JMH benchmark designed to stress every cardinality + capacity dimension of the
 * metrics subsystem at once.
 *
 * <p>The metrics aggregator is supposed to be bounded by design:
 *
 * <ul>
 *   <li>{@link AggregateTable} caps total entries at {@code tracerMetricsMaxAggregates} (default
 *       2048) and rejects further inserts when full.
 *   <li>Each cardinality handler caps distinct values per reporting cycle; overflow collapses to
 *       {@code blocked_by_tracer}.
 *   <li>The producer/consumer inbox is a fixed-size MPSC queue ({@code tracerMetricsMaxPending},
 *       default 2048); when full, producer {@code offer} returns false and the snapshot is dropped
 *       via {@link HealthMetrics#onStatsInboxFull()}.
 *   <li>Histograms use {@code CollapsingLowestDenseStore(1024)} -- bounded per-histogram memory.
 *   <li>Cardinality handlers are flat open-addressed tables of fixed capacity -- no allocation on
 *       the producer thread; allocation only on the consumer (handler reset clears, doesn't
 *       reallocate).
 * </ul>
 *
 * <p>This benchmark hammers all of those bounds simultaneously with 8 producer threads, unique
 * labels per op (so handlers cap and the table fills+evicts repeatedly), random durations across a
 * wide range (so histograms accept many distinct bins), and random {@code error}/{@code topLevel}
 * flags (so both histograms are exercised). After the run, prints the drop counters so you can
 * verify the subsystem stayed bounded under attack.
 *
 * <p>What "OOM the metrics subsystem" looks like if the bounds break: producer-thread allocation
 * would grow unbounded (snapshots faster than inbox can drain produces dropped snapshots, not heap
 * growth); aggregator-thread heap would grow if entries weren't capped, if handlers grew past their
 * cap, or if histograms grew past their dense-store limit.
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
  public void tearDown() {
    aggregator.close();
    System.err.println(
        "[ADVERSARIAL] snapshots offered (across all threads, both forks combined for this run):");
    System.err.println(
        "  onStatsInboxFull         = "
            + health.inboxFull
            + "   (snapshots dropped because the MPSC inbox was full)");
    System.err.println(
        "  onStatsAggregateDropped  = "
            + health.aggregateDropped
            + "   (snapshots dropped because the AggregateTable was full with no stale entry)");
    System.err.println(
        "  onClientStatTraceComputed total = "
            + health.traceComputedCalls
            + "  spans counted = "
            + health.totalSpansCounted);
  }

  @Benchmark
  public void publish(ThreadState ts, Blackhole blackhole) {
    int idx = ts.cursor++;
    ThreadLocalRandom rng = ThreadLocalRandom.current();

    // Mix indices so labels don't fall into linear order in the handler tables. Distinct labels
    // exceed every cap (RESOURCE=512, OPERATION=128, SERVICE=128, peer.hostname=512), so handlers
    // saturate fast and most ops resolve to the blocked-by-tracer sentinel.
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
   * Counts what gets dropped. The aggregator publishes onto these counters from many threads, so
   * the fields are {@code volatile long} with non-atomic increments -- precise counts aren't the
   * point, order-of-magnitude is.
   */
  static final class CountingHealthMetrics extends HealthMetrics {
    volatile long inboxFull;
    volatile long aggregateDropped;
    volatile long traceComputedCalls;
    volatile long totalSpansCounted;
    volatile long tagCardinalityBlocked;

    @Override
    public void onStatsInboxFull() {
      inboxFull++;
    }

    @Override
    public void onStatsAggregateDropped() {
      aggregateDropped++;
    }

    @Override
    public void onClientStatTraceComputed(int counted, int total, boolean dropped) {
      traceComputedCalls++;
      totalSpansCounted += counted;
    }

    @Override
    public void onTagCardinalityBlocked(String tag, long count) {
      tagCardinalityBlocked += count;
    }
  }
}

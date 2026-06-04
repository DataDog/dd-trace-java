package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.WellKnownTags;
import datadog.trace.common.metrics.AdversarialMetricsBenchmark.CountingHealthMetrics;
import datadog.trace.core.CoreSpan;
import de.thetaphi.forbiddenapis.SuppressForbidden;
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
 * Cardinality-isolation companion to {@link AdversarialMetricsBenchmark}: only the {@code
 * peer.hostname} tag value varies; {@code service}, {@code operation}, and {@code resource} are
 * pinned to single values. Pairing this with the adversarial bench (all four dimensions
 * high-cardinality) and {@link HighCardinalityResourceMetricsBenchmark} (only resource
 * high-cardinality) lets you attribute any throughput delta to a specific axis.
 *
 * <p>This isolates the peer-tag-encoding hot path: {@code PEER_TAGS_CACHE} lookups, the per-tag
 * UTF8 encoding of {@code "name:value"}, and the parallel-array capture inside the producer's
 * {@code SpanSnapshot} build. With {@code 0x7FFF} (~32K) distinct hostnames the cache thrashes
 * heavily and exceeds the default {@code tracerMetricsMaxAggregates=2048} so the LRU evicts
 * continuously.
 *
 * <p>Random {@code error}/{@code topLevel}/duration to keep histogram load comparable; only the
 * cardinality profile changes.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Threads(8)
@Fork(value = 1)
public class HighCardinalityPeerMetricsBenchmark {

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
    System.err.println(
        "[HIGH_CARD_PEER] drops over the trial (8 threads, warmup + measurement combined):");
    System.err.println("  onStatsInboxFull         = " + health.inboxFull.sum());
    System.err.println("  onStatsAggregateDropped  = " + health.aggregateDropped.sum());
  }

  @Benchmark
  public void publish(ThreadState ts, Blackhole blackhole) {
    int idx = ts.cursor++;
    ThreadLocalRandom rng = ThreadLocalRandom.current();

    int scrambled = idx * 0x9E3779B1;
    String hostname = "host-" + ((scrambled >>> 12) & 0x7FFF);
    boolean error = (idx & 7) == 0;
    boolean topLevel = (idx & 3) == 0;
    long durationNanos = 1L + (rng.nextLong() & 0x3FFFFFFFL);

    SimpleSpan span =
        new SimpleSpan("svc", "op", "res", "web", true, topLevel, error, 0, durationNanos, 200);
    span.setTag(SPAN_KIND, SPAN_KIND_CLIENT);
    span.setTag("peer.hostname", hostname);

    List<CoreSpan<?>> trace = Collections.singletonList(span);
    blackhole.consume(aggregator.publish(trace));
  }
}

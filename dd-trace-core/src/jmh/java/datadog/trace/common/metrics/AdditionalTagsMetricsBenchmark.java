package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.WellKnownTags;
import datadog.trace.core.CoreSpan;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Regression benchmark for the additional-tags hot path; {@code limitsEnabled=true} is slower here
 * because it records more (sentinel collapses), not because limiting is expensive.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Threads(8)
@Fork(1)
public class AdditionalTagsMetricsBenchmark {

  private ClientStatsAggregator aggregator;
  private AdversarialMetricsBenchmark.CountingHealthMetrics health;

  @Param({"false", "true"})
  public boolean limitsEnabled;

  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;
  }

  @Setup
  public void setup() {
    this.health = new AdversarialMetricsBenchmark.CountingHealthMetrics();
    AdditionalTagsSchema additionalTagsSchema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region", "tenant_id")),
            MetricCardinalityLimits.ADDITIONAL_TAG_VALUE,
            limitsEnabled);
    this.aggregator =
        new ClientStatsAggregator(
            new WellKnownTags("", "", "", "", "", ""),
            Collections.emptySet(),
            additionalTagsSchema,
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
    System.err.println("[ADDITIONAL-TAGS] counters (across all threads, single fork):");
    System.err.println("  onStatsInboxFull         = " + health.inboxFull.sum());
    System.err.println("  onStatsAggregateDropped  = " + health.aggregateDropped.sum());
  }

  @Benchmark
  public void publish(ThreadState ts, Blackhole blackhole) {
    int idx = ts.cursor++;
    ThreadLocalRandom rng = ThreadLocalRandom.current();

    int scrambled = idx * 0x9E3779B1;
    String region = "region-" + ((scrambled >>> 4) & 0xFFFF);
    String tenant = "tenant-" + ((scrambled >>> 16) & 0xFFFF);
    long durationNanos = 1L + (rng.nextLong() & 0x3FFFFFFFL);

    SimpleSpan span =
        new SimpleSpan("svc", "op", "res", "web", true, true, false, 0, durationNanos, 200);
    span.setTag(SPAN_KIND, SPAN_KIND_CLIENT);
    span.setTag("region", region);
    span.setTag("tenant_id", tenant);

    List<CoreSpan<?>> trace = Collections.singletonList(span);
    blackhole.consume(aggregator.publish(trace));
  }
}

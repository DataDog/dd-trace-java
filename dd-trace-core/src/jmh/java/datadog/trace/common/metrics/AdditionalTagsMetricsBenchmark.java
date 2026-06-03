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
 * JMH benchmark exercising the span-derived primary tags pipeline added in CSS v1.3.0. Parallel to
 * {@link AdversarialMetricsBenchmark} but configures two additional-tag keys (each with a per-key
 * cardinality cap of {@link MetricCardinalityLimits#ADDITIONAL_TAG_VALUE}) and generates unique
 * values per op so the cap saturates fast. The benchmark measures the cost of:
 *
 * <ul>
 *   <li>producer-side capture: {@code ClientStatsAggregator.captureAdditionalTagValues} walks the
 *       schema and pulls each key via {@code unsafeGetTag}.
 *   <li>aggregator-side canonicalization: {@code AdditionalTagsSchema.register(i, value)} runs a
 *       {@link TagCardinalityHandler} probe + insert, returning the per-key blocked sentinel once
 *       the per-cycle value budget is exhausted.
 *   <li>cycle-reset flush: at every reporting cycle, the schema fires one {@code
 *       HealthMetrics.onTagCardinalityBlocked(name, count)} per affected key.
 * </ul>
 *
 * <p>The aim is not absolute throughput numbers but a regression guard for the additional-tags hot
 * path: any future refactor that adds a tag-map lookup, allocates per call, or pulls the
 * sentinel-materialization onto the hot path should show up as a step change here.
 *
 * <p><b>Interpreting the {@code limitsEnabled} parameter.</b> The two arms are NOT a fair "cost of
 * limiting" comparison and should not be read as one. With this benchmark's unbounded distinct
 * values, the per-key budget saturates almost immediately and the two modes diverge into different
 * downstream behavior, not just a different branch in {@code register}:
 *
 * <ul>
 *   <li>{@code limitsEnabled=true}: every over-budget span collapses to the single {@code
 *       "<key>:blocked_by_tracer"} sentinel entry, so {@code findOrInsert} always hits a live entry
 *       and {@code recordOneDuration} (a DDSketch histogram insert) runs for every drained span.
 *   <li>{@code limitsEnabled=false}: every over-budget value canonicalizes to a distinct entry, so
 *       the table saturates at {@code maxAggregates} and most subsequent spans are dropped at
 *       {@code findOrInsert} -- never reaching the histogram.
 * </ul>
 *
 * <p>So {@code limitsEnabled=true} measures lower throughput here precisely because it does MORE
 * useful work per span (it keeps the masked data and records it) where the disabled arm drops the
 * overflow. A 2026-06-03 run (3 forks, -prof gc) measured {@code false} at ~19.8M ops/s / 820 B/op
 * and {@code true} at ~12.4M ops/s / 888 B/op -- the higher per-op allocation under limits is the
 * histogram recording, not the sentinel path. Throughput CIs were wide (>20%); the per-op
 * allocation figures are the reliable signal. A production workload with a bounded value set never
 * saturates the budget and sees neither arm's overflow behavior (cf. {@code
 * HighCardinalityResourceMetricsBenchmark}, which is at parity with limits on/off).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Threads(8)
@Fork(value = 1)
public class AdditionalTagsMetricsBenchmark {

  private ClientStatsAggregator aggregator;
  private AdversarialMetricsBenchmark.CountingHealthMetrics health;

  /**
   * Whether the {@link TagCardinalityHandler}s inside {@link AdditionalTagsSchema} substitute the
   * {@code blocked_by_tracer} sentinel once the per-key budget is exhausted. JMH runs both values
   * within the same fork so the two modes see equivalent thermal conditions.
   */
  @Param({"false", "true"})
  public boolean limitsEnabled;

  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;
  }

  @Setup
  public void setup() {
    this.health = new AdversarialMetricsBenchmark.CountingHealthMetrics();
    // Two configured additional tags. Each key gets a TagCardinalityHandler capped at
    // MetricCardinalityLimits.ADDITIONAL_TAG_VALUE (512) distinct values per cycle. The benchmark
    // generates 65k distinct values per key so the cap saturates quickly and most ops return the
    // blocked sentinel -- that's the contention we want to measure.
    AdditionalTagsSchema additionalTagsSchema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region", "tenant_id")), this.health, limitsEnabled);
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

    // Distinct values per op -- 65k regions × 65k tenants × random durations. With the per-key cap
    // (ADDITIONAL_TAG_VALUE = 512), the first 512 distinct values per key admit; the rest collapse
    // to the blocked sentinel and increment the per-tag block counter via the schema's flush path.
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

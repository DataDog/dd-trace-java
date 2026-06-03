package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.WellKnownTags;
import datadog.trace.core.CoreSpan;
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
 * cardinality cap of 100) and generates unique values per op so the cap saturates fast. The
 * benchmark measures the cost of:
 *
 * <ul>
 *   <li>producer-side capture: {@code ClientStatsAggregator.captureAdditionalTagValues} walks the
 *       schema and pulls each key via {@code unsafeGetTag}.
 *   <li>aggregator-side canonicalization: {@code AdditionalTagsSchema.register(i, value)} runs
 *       length-check, handler probe + insert, isBlockedResult check, and per-tag block-counter
 *       accumulation.
 *   <li>cycle-reset flush: at every reporting cycle, the schema fires one {@code
 *       HealthMetrics.onTagCardinalityBlocked(name, count)} per affected key.
 * </ul>
 *
 * <p>The aim is not absolute throughput numbers but a regression guard for the additional-tags hot
 * path: any future refactor that adds a tag-map lookup, allocates per call, or pulls the
 * sentinel-materialization onto the hot path should show up as a step change here.
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

  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;
  }

  @Setup
  public void setup() {
    this.health = new AdversarialMetricsBenchmark.CountingHealthMetrics();
    // Two configured additional tags. The schema caps per-key cardinality at 100, so over the run
    // most ops will collapse onto the per-key "<key>:blocked_by_tracer" sentinel -- the contention
    // we want to measure.
    AdditionalTagsSchema additionalTagsSchema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region", "tenant_id")), 100, this.health);
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
  public void tearDown() {
    aggregator.close();
    System.err.println("[ADDITIONAL-TAGS] counters (across all threads, single fork):");
    System.err.println("  onStatsInboxFull         = " + health.inboxFull);
    System.err.println("  onStatsAggregateDropped  = " + health.aggregateDropped);
    System.err.println("  traceComputedCalls       = " + health.traceComputedCalls);
    System.err.println("  totalSpansCounted        = " + health.totalSpansCounted);
    System.err.println("  tagCardinalityBlocked    = " + health.tagCardinalityBlocked);
  }

  @Benchmark
  public void publish(ThreadState ts, Blackhole blackhole) {
    int idx = ts.cursor++;
    ThreadLocalRandom rng = ThreadLocalRandom.current();

    // Distinct values per op -- 65k regions × 65k tenants × random durations.  Cardinality cap is
    // 100 per key, so the first 100 distinct values per key admit, the rest collapse to the
    // blocked sentinel and increment the per-tag block counter via the schema's flush path.
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

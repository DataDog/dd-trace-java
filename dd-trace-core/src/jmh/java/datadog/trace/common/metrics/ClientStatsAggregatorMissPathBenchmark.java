package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.WellKnownTags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Miss-path variant of {@link ClientStatsAggregatorBenchmark}. Each op publishes a single-span
 * trace from a pre-built pool where every span has a unique (service, operation, resource) tuple.
 * After cardinality budgets fill, fields canonicalize to the {@code blocked_by_tracer} sentinel,
 * but the producer still allocates a {@link SpanSnapshot} per op and enqueues it for the aggregator
 * -- so the steady state exercises the per-op publish allocations + the consumer's
 * canonicalize/match work, not the hit-path-only pattern of the other benchmarks.
 *
 * <p>Run with {@code -prof gc} to compare allocation rates against master's {@code
 * ConflatingMetricsAggregator}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class ClientStatsAggregatorMissPathBenchmark {

  private static final int POOL_SIZE = 4096;

  private final DDAgentFeaturesDiscovery featuresDiscovery =
      new ClientStatsAggregatorBenchmark.FixedAgentFeaturesDiscovery(
          Collections.singleton("peer.hostname"), Collections.emptySet());
  private final ClientStatsAggregator aggregator =
      new ClientStatsAggregator(
          new WellKnownTags("", "", "", "", "", ""),
          Collections.emptySet(),
          featuresDiscovery,
          HealthMetrics.NO_OP,
          new ClientStatsAggregatorBenchmark.NullSink(),
          2048,
          2048,
          false);

  private final List<List<CoreSpan<?>>> pool = generatePool(POOL_SIZE);
  private int cursor;

  static List<List<CoreSpan<?>>> generatePool(int n) {
    List<List<CoreSpan<?>>> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      SimpleSpan span =
          new SimpleSpan(
              "svc-" + i, "op-" + i, "res-" + i, "type-" + (i & 7), true, true, false, 0, 10, -1);
      span.setTag(SPAN_KIND, SPAN_KIND_CLIENT);
      span.setTag("peer.hostname", "host-" + i);
      out.add(Collections.singletonList(span));
    }
    return out;
  }

  @Benchmark
  public void benchmark(Blackhole blackhole) {
    int idx = cursor;
    cursor = (idx + 1) % POOL_SIZE;
    blackhole.consume(aggregator.publish(pool.get(idx)));
  }
}

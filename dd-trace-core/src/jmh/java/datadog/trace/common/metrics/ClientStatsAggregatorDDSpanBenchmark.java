package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.WellKnownTags;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.Strings;
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
 * Parallels {@link ClientStatsAggregatorBenchmark} but uses real {@link DDSpan} instances instead
 * of the lightweight {@code SimpleSpan} mock, so the JIT exercises the production {@link
 * CoreSpan#isKind} path (cached span.kind ordinal + bit-test) rather than the groovy mock's
 * dispatch.
 *
 * <p>SpanKindFilter rollout result vs. the pre-bitmask code on master: ~1.3% faster on the
 * production path, with tighter fork-to-fork variance. The CIs overlap so the headline number sits
 * inside noise, but the centers move the right way and the new path is structurally cheaper (byte
 * read + bit-test vs tag-map read + HashSet.contains). <code>
 * MacBook M1 (Java 21), 2 forks x 5 iterations x 15s, AverageTime
 *
 * Branch       Score (avg)   CI (99.9%)
 * master       6.428 ± 0.189 µs/op  [6.239, 6.617]
 * this branch  6.343 ± 0.115 µs/op  [6.228, 6.458]
 * </code>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class ClientStatsAggregatorDDSpanBenchmark {

  private static final CoreTracer TRACER =
      CoreTracer.builder().writer(new NoopWriter()).strictTraceWrites(false).build();

  private final DDAgentFeaturesDiscovery featuresDiscovery =
      new ClientStatsAggregatorBenchmark.FixedAgentFeaturesDiscovery(
          Collections.singleton("peer.hostname"), Collections.emptySet());
  private final ClientStatsAggregator aggregator =
      new ClientStatsAggregator(
          new WellKnownTags("", "", "", "", "", ""),
          Collections.emptySet(),
          AdditionalTagsSchema.EMPTY,
          featuresDiscovery,
          HealthMetrics.NO_OP,
          new ClientStatsAggregatorBenchmark.NullSink(),
          2048,
          2048,
          false);
  private final List<CoreSpan<?>> spans = generateTrace(64);

  static List<CoreSpan<?>> generateTrace(int len) {
    final List<CoreSpan<?>> trace = new ArrayList<>();
    for (int i = 0; i < len; i++) {
      DDSpan span = (DDSpan) TRACER.startSpan("benchmark", "op");
      span.setTag(SPAN_KIND, SPAN_KIND_CLIENT);
      span.setTag("peer.hostname", Strings.random(10));
      // Fix duration; bypasses the wall clock and avoids per-fork drift.
      span.finishWithDuration(10);
      trace.add(span);
    }
    return trace;
  }

  static class NoopWriter implements Writer {
    @Override
    public void write(List<DDSpan> trace) {}

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }

  @Benchmark
  public void benchmark(Blackhole blackhole) {
    blackhole.consume(aggregator.publish(spans));
  }
}

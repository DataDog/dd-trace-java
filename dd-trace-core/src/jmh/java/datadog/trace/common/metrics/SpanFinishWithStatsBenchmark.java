package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.WellKnownTags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.Strings;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures the foreground thread cost of publishing span stats. With the background-stats
 * optimization, the foreground thread should only extract lightweight SpanStatsData and offer to
 * the inbox queue, while the expensive MetricKey construction and HashMap operations happen on the
 * background aggregator thread.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class SpanFinishWithStatsBenchmark {

  private static final Set<String> PEER_TAGS = Collections.singleton("peer.hostname");

  private final DDAgentFeaturesDiscovery featuresDiscovery =
      new ConflatingMetricsAggregatorBenchmark.FixedAgentFeaturesDiscovery(
          PEER_TAGS, Collections.emptySet());

  private ConflatingMetricsAggregator aggregator;

  private final List<CoreSpan<?>> smallTrace = generateTrace(4);
  private final List<CoreSpan<?>> mediumTrace = generateTrace(16);
  private final List<CoreSpan<?>> largeTrace = generateTrace(64);

  @Setup(Level.Trial)
  public void setup() {
    aggregator =
        new ConflatingMetricsAggregator(
            new WellKnownTags("", "", "", "", "", ""),
            Collections.emptySet(),
            featuresDiscovery,
            HealthMetrics.NO_OP,
            new NullSink(),
            2048,
            2048,
            false);
    aggregator.start();
  }

  @TearDown(Level.Trial)
  public void teardown() {
    if (aggregator != null) {
      aggregator.close();
    }
  }

  static List<CoreSpan<?>> generateTrace(int len) {
    final List<CoreSpan<?>> trace = new ArrayList<>();
    for (int i = 0; i < len; i++) {
      SimpleSpan span = new SimpleSpan("", "", "", "", true, true, false, 0, 10, -1);
      span.setTag("peer.hostname", Strings.random(10));
      trace.add(span);
    }
    return trace;
  }

  static class NullSink implements Sink {
    @Override
    public void register(EventListener listener) {}

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}
  }

  @Benchmark
  public void publishSmallTrace(Blackhole blackhole) {
    blackhole.consume(aggregator.publish(smallTrace));
  }

  @Benchmark
  public void publishMediumTrace(Blackhole blackhole) {
    blackhole.consume(aggregator.publish(mediumTrace));
  }

  @Benchmark
  public void publishLargeTrace(Blackhole blackhole) {
    blackhole.consume(aggregator.publish(largeTrace));
  }

  /** Multi-threaded benchmark to measure contention under concurrent publishing. */
  @State(Scope.Benchmark)
  @Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
  @Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
  @BenchmarkMode(Mode.Throughput)
  @OutputTimeUnit(MICROSECONDS)
  @Threads(8)
  @Fork(value = 1)
  public static class ConcurrentPublish {

    private ConflatingMetricsAggregator aggregator;
    private final List<CoreSpan<?>> trace = generateTrace(16);

    @Setup(Level.Trial)
    public void setup() {
      DDAgentFeaturesDiscovery features =
          new ConflatingMetricsAggregatorBenchmark.FixedAgentFeaturesDiscovery(
              PEER_TAGS, Collections.emptySet());
      aggregator =
          new ConflatingMetricsAggregator(
              new WellKnownTags("", "", "", "", "", ""),
              Collections.emptySet(),
              features,
              HealthMetrics.NO_OP,
              new NullSink(),
              2048,
              2048,
              false);
      aggregator.start();
    }

    @TearDown(Level.Trial)
    public void teardown() {
      if (aggregator != null) {
        aggregator.close();
      }
    }

    @Benchmark
    public void publishConcurrent(Blackhole blackhole) {
      blackhole.consume(aggregator.publish(trace));
    }
  }
}

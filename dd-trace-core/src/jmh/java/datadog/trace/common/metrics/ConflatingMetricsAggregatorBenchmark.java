package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Monitoring;
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
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class ConflatingMetricsAggregatorBenchmark {
  private final DDAgentFeaturesDiscovery featuresDiscovery =
      new FixedAgentFeaturesDiscovery(
          Collections.singleton("peer.hostname"), Collections.emptySet());
  private final ConflatingMetricsAggregator aggregator =
      new ConflatingMetricsAggregator(
          new WellKnownTags("", "", "", "", "", ""),
          Collections.emptySet(),
          featuresDiscovery,
          HealthMetrics.NO_OP,
          new NullSink(),
          2048,
          2048);
  private final List<CoreSpan<?>> spans = generateTrace(64);

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

  static class FixedAgentFeaturesDiscovery extends DDAgentFeaturesDiscovery {
    private final Set<String> peerTags;
    private final Set<String> spanKinds;

    public FixedAgentFeaturesDiscovery(Set<String> peerTags, Set<String> spanKinds) {
      // create a fixed discovery with metrics enabled
      super(null, Monitoring.DISABLED, null, false, false, true);
      this.peerTags = peerTags;
      this.spanKinds = spanKinds;
    }

    @Override
    public void discover() {
      // do nothing
    }

    @Override
    public boolean supportsMetrics() {
      return true;
    }

    @Override
    public Set<String> peerTags() {
      return peerTags;
    }
  }

  @Benchmark
  public void benchmark(Blackhole blackhole) {
    blackhole.consume(aggregator.publish(spans));
  }
}

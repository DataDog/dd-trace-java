package datadog.trace.core.datastreams;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.Pair;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

/**
 * The Data Streams work an integration does per call with Data Streams off in config, against a
 * started monitor whose inbox thread is draining, as in an application. Kafka's offset commit
 * builds tags ({@link #commitTags}) and tracks a backlog ({@link #trackBacklog}) per partition;
 * schema registry serdes report usage per record ({@link #schemaRegistryUsage}); producers set and
 * clear the cluster id thread-local per send ({@link #clusterIdSetAndClear} against {@link
 * #clusterIdClearOnly}).
 *
 * <p>Results (MacBook, JDK 21, default flags, 3 forks, 4 threads; the machine was busy, so treat
 * the times as approximate):
 *
 * <pre>
 * Benchmark             no guard (before)      guarded (after)
 *                       ns/op        B/op      ns/op        B/op
 * trackBacklog          40.6 +- 4.1    40      0.89 +- 0.02    0
 * schemaRegistryUsage   40.5 +- 2.6    48      0.88 +- 0.01    0
 * commitTags           191.8 +- 3.1   672      (skipped by the Kafka commit guard)
 * clusterIdSetAndClear   2.83 +- 0.07   0      1.41 +- 0.07    0 (clusterIdClearOnly)
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(4)
@State(Scope.Benchmark)
public class DataStreamsOffBenchmark {
  static final ThreadLocal<String> CLUSTER_ID = new ThreadLocal<>();

  final String topic = "orders";
  final String clusterId = "lkc-6ojk2q";
  final String group = "order-processor";

  final DataStreamsTags commitTags =
      DataStreamsTags.createWithPartition("kafka_commit", topic, "3", clusterId, group);

  DefaultDataStreamsMonitoring dataStreams;

  @Setup(Level.Trial)
  public void setup() {
    dataStreams =
        new DefaultDataStreamsMonitoring(
            new NoopSink(),
            new SupportingFeatures(),
            SystemTimeSource.INSTANCE,
            () -> DataStreamsOff.INSTANCE,
            (buckets, serviceNameOverride) -> {},
            TimeUnit.SECONDS.toNanos(10));
    dataStreams.start();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataStreams.close();
  }

  /** The tags a Kafka offset commit builds per partition before tracking its backlog. */
  @Benchmark
  public DataStreamsTags commitTags() {
    return DataStreamsTags.createWithPartition(
        "kafka_commit", topic, String.valueOf(3), clusterId, group);
  }

  @Benchmark
  public void trackBacklog() {
    dataStreams.trackBacklog(commitTags, 123_456L);
  }

  @Benchmark
  public void schemaRegistryUsage() {
    dataStreams.reportSchemaRegistryUsage(topic, clusterId, 42, true, false, "serialize");
  }

  @Benchmark
  public void clusterIdSetAndClear() {
    CLUSTER_ID.set(clusterId);
    CLUSTER_ID.set(null);
  }

  @Benchmark
  public void clusterIdClearOnly() {
    CLUSTER_ID.set(null);
  }

  /** Agent supports Data Streams, so only the config turns it off. */
  static final class SupportingFeatures extends DDAgentFeaturesDiscovery {
    SupportingFeatures() {
      super(null, Monitoring.DISABLED, null, null, false, false);
    }

    @Override
    public void discoverIfOutdated() {}

    @Override
    public String getDataStreamsEndpoint() {
      return "v0.1/pipeline_stats";
    }

    @Override
    public boolean supportsDataStreams() {
      return true;
    }
  }

  static final class NoopSink implements Sink {
    @Override
    public void register(EventListener listener) {}

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}
  }

  static final class DataStreamsOff implements TraceConfig {
    static final DataStreamsOff INSTANCE = new DataStreamsOff();

    @Override
    public boolean isTraceEnabled() {
      return true;
    }

    @Override
    public boolean isRuntimeMetricsEnabled() {
      return false;
    }

    @Override
    public boolean isLogsInjectionEnabled() {
      return false;
    }

    @Override
    public boolean isDataStreamsEnabled() {
      return false;
    }

    @Override
    public Map<String, String> getServiceMapping() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getRequestHeaderTags() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getResponseHeaderTags() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getBaggageMapping() {
      return Collections.emptyMap();
    }

    @Override
    public Double getTraceSampleRate() {
      return null;
    }

    @Override
    public Map<String, String> getTracingTags() {
      return Collections.emptyMap();
    }

    @Override
    public Pair<String, CharSequence> getPreferredServiceNameAndSource() {
      return null;
    }

    @Override
    public List<? extends SpanSamplingRule> getSpanSamplingRules() {
      return Collections.emptyList();
    }

    @Override
    public List<? extends TraceSamplingRule> getTraceSamplingRules() {
      return Collections.emptyList();
    }

    @Override
    public List<DataStreamsTransactionExtractor> getDataStreamsTransactionExtractors() {
      return Collections.emptyList();
    }
  }
}

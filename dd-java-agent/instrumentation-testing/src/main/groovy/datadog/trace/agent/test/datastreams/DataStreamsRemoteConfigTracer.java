package datadog.trace.agent.test.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.datastreams.KafkaConfigReport;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopTraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.datastreams.StatsBucket;
import datadog.trace.core.datastreams.StatsGroup;
import datadog.trace.test.util.PollingConditions;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test tracer with a recording Data Streams monitor that reads the tracer's live config, as in
 * production, so it can be toggled the way remote config does.
 */
public final class DataStreamsRemoteConfigTracer {
  private final CoreTracer tracer;
  private final ListWriter writer;
  private final DefaultDataStreamsMonitoring dataStreamsMonitoring;
  private final RecordingDatastreamsPayloadWriter dataStreamsWriter;

  private DataStreamsRemoteConfigTracer(
      CoreTracer tracer,
      ListWriter writer,
      DefaultDataStreamsMonitoring dataStreamsMonitoring,
      RecordingDatastreamsPayloadWriter dataStreamsWriter) {
    this.tracer = tracer;
    this.writer = writer;
    this.dataStreamsMonitoring = dataStreamsMonitoring;
    this.dataStreamsWriter = dataStreamsWriter;
  }

  /** Closes {@code previous} and installs a tracer writing to {@code writer} as the global one. */
  public static DataStreamsRemoteConfigTracer install(TracerAPI previous, ListWriter writer) {
    if (previous != null) {
      previous.close();
    }
    AtomicReference<CoreTracer> tracerRef = new AtomicReference<>();
    RecordingDatastreamsPayloadWriter dataStreamsWriter = new RecordingDatastreamsPayloadWriter();
    // Same live config as production (CoreTracer passes this::captureTraceConfig)
    DefaultDataStreamsMonitoring dataStreamsMonitoring =
        new DefaultDataStreamsMonitoring(
            new NoopSink(),
            new MockFeaturesDiscovery(true),
            SystemTimeSource.INSTANCE,
            () -> {
              CoreTracer tracer = tracerRef.get();
              return tracer == null ? NoopTraceConfig.INSTANCE : tracer.captureTraceConfig();
            },
            dataStreamsWriter,
            TimeUnit.MILLISECONDS.toNanos(50));
    CoreTracer tracer =
        CoreTracer.builder()
            .writer(writer)
            .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
            .strictTraceWrites(true)
            .dataStreamsMonitoring(dataStreamsMonitoring)
            .build();
    tracerRef.set(tracer);
    TracerInstaller.forceInstallGlobalTracer(tracer);
    return new DataStreamsRemoteConfigTracer(
        tracer, writer, dataStreamsMonitoring, dataStreamsWriter);
  }

  public CoreTracer tracer() {
    return tracer;
  }

  public DefaultDataStreamsMonitoring dataStreamsMonitoring() {
    return dataStreamsMonitoring;
  }

  /** Drops recorded payloads and pending stats. */
  public void clear() {
    dataStreamsWriter.clear();
    dataStreamsMonitoring.clear();
  }

  /**
   * Updates the tracer's dynamic config like remote config, then waits for the DSM monitor. No
   * public API applies a dynamic update, so the field is read reflectively.
   */
  public void toggleDataStreams(boolean enabled) throws Exception {
    Field dynamicConfig = CoreTracer.class.getDeclaredField("dynamicConfig");
    dynamicConfig.setAccessible(true);
    ((DynamicConfig<?>) dynamicConfig.get(tracer)).current().setDataStreamsEnabled(enabled).apply();
    new PollingConditions(5)
        .eventually(() ->
            assertNotEquals(
                enabled, dataStreamsMonitoring.newPathwayContext() instanceof NoopPathwayContext));
  }

  public boolean hasGroup(String... tags) {
    return group(tags) != null;
  }

  /** The first recorded group having all {@code tags}, or null if none was recorded. */
  public StatsGroup group(String... tags) {
    for (StatsGroup group : dataStreamsWriter.getGroups()) {
      if (group.getTags().hasAllTags(tags)) {
        return group;
      }
    }
    return null;
  }

  /**
   * Group count once in-flight flushes have landed. Waits a fixed window since it checks for an
   * absence; reports run every 50 ms bucket.
   */
  public int settledGroupCount() {
    AtomicInteger count = new AtomicInteger();
    new PollingConditions()
        .initialDelay(0.5)
        .eventually(() -> count.set(dataStreamsWriter.getGroups().size()));
    return count.get();
  }

  /** Runs {@code pollClients} so client metadata refreshes, until both Kafka configs report. */
  public void waitForKafkaConfigReports(Runnable pollClients) {
    new PollingConditions(15)
        .eventually(() -> {
          pollClients.run();
          Set<String> types = new HashSet<>();
          for (StatsBucket bucket : dataStreamsWriter.getPayloads()) {
            for (KafkaConfigReport report : bucket.getKafkaConfigs()) {
              if (!report.getConfig().isEmpty()) {
                types.add(report.getType());
              }
            }
          }
          assertTrue(types.containsAll(Arrays.asList("kafka_producer", "kafka_consumer")));
        });
  }

  /**
   * Runs {@code pollClients} until a consumer group membership reports, then for a settling window
   * where duplicates would land; returns the membership reports.
   */
  public List<KafkaConfigReport> waitForKafkaConsumerGroupMemberReports(Runnable pollClients) {
    new PollingConditions(15)
        .eventually(() -> {
          pollClients.run();
          assertFalse(kafkaConsumerGroupMemberReports().isEmpty());
        });
    long settledAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < settledAt) {
      pollClients.run();
    }
    return kafkaConsumerGroupMemberReports();
  }

  /** Reported consumer group memberships, which carry a member ID instead of a config. */
  private List<KafkaConfigReport> kafkaConsumerGroupMemberReports() {
    List<KafkaConfigReport> reports = new ArrayList<>();
    for (StatsBucket bucket : dataStreamsWriter.getPayloads()) {
      for (KafkaConfigReport report : bucket.getKafkaConfigs()) {
        if (!report.getMemberId().isEmpty()) {
          reports.add(report);
        }
      }
    }
    return reports;
  }

  /** The first non-empty reported Kafka config of {@code type}, or null if none was reported. */
  public Map<String, String> kafkaConfig(String type) {
    for (StatsBucket bucket : dataStreamsWriter.getPayloads()) {
      for (KafkaConfigReport report : bucket.getKafkaConfigs()) {
        if (type.equals(report.getType()) && !report.getConfig().isEmpty()) {
          return report.getConfig();
        }
      }
    }
    return null;
  }

  /** Waits for, then returns, the single written span named {@code operationName}. */
  public DDSpan singleSpan(String operationName) {
    new PollingConditions(5)
        .delay(0.02)
        .eventually(() -> assertFalse(spans(operationName).isEmpty()));
    List<DDSpan> spans = spans(operationName);
    assertEquals(1, spans.size());
    return spans.get(0);
  }

  /** Written spans named {@code operationName}. */
  private List<DDSpan> spans(String operationName) {
    List<DDSpan> spans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if (operationName.equals(span.getOperationName().toString())) {
          spans.add(span);
        }
      }
    }
    return spans;
  }

  private static final class NoopSink implements Sink {
    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}

    @Override
    public void register(EventListener listener) {}
  }
}

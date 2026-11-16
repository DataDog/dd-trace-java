package datadog.trace.agent.test.datastreams;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopTraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.datastreams.StatsGroup;
import datadog.trace.test.util.PollingConditions;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test tracer with a recording Data Streams monitor that reads the tracer's live config, as in
 * production, so it can be toggled the way remote config does.
 */
public final class DataStreamsRemoteConfigTracer {
  private final CoreTracer tracer;
  private final DefaultDataStreamsMonitoring dataStreamsMonitoring;
  private final RecordingDatastreamsPayloadWriter dataStreamsWriter;

  private DataStreamsRemoteConfigTracer(
      CoreTracer tracer,
      DefaultDataStreamsMonitoring dataStreamsMonitoring,
      RecordingDatastreamsPayloadWriter dataStreamsWriter) {
    this.tracer = tracer;
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
    return new DataStreamsRemoteConfigTracer(tracer, dataStreamsMonitoring, dataStreamsWriter);
  }

  public CoreTracer tracer() {
    return tracer;
  }

  public DefaultDataStreamsMonitoring dataStreamsMonitoring() {
    return dataStreamsMonitoring;
  }

  public RecordingDatastreamsPayloadWriter dataStreamsWriter() {
    return dataStreamsWriter;
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
    for (StatsGroup group : dataStreamsWriter.getGroups()) {
      if (group.getTags().hasAllTags(tags)) {
        return true;
      }
    }
    return false;
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

  private static final class NoopSink implements Sink {
    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}

    @Override
    public void register(EventListener listener) {}
  }
}

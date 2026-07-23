package datadog.trace.core.datastreams;

import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoring.FEATURE_CHECK_INTERVAL_NANOS;
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoringTestBridge.getThreadState;
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoringTestBridge.isInboxEmpty;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.KafkaConfigReport;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.SchemaRegistryUsage;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.DDCoreJavaSpecification;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class DefaultDataStreamsMonitoringTest extends DDCoreJavaSpecification {

  private static final long DEFAULT_BUCKET_DURATION_NANOS =
      Config.get().getDataStreamsBucketDurationNanoseconds();

  @BeforeAll
  static void registerHistograms() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  private static void awaitIdle(DefaultDataStreamsMonitoring dataStreams)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < deadline) {
      if (isInboxEmpty(dataStreams) && getThreadState(dataStreams) != Thread.State.RUNNABLE) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(isInboxEmpty(dataStreams));
    assertNotEquals(Thread.State.RUNNABLE, getThreadState(dataStreams));
  }

  private static void awaitBuckets(
      DefaultDataStreamsMonitoring dataStreams, CapturingPayloadWriter writer, int expectedBuckets)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < deadline) {
      if (writer.buckets.size() == expectedBuckets
          && isInboxEmpty(dataStreams)
          && getThreadState(dataStreams) != Thread.State.RUNNABLE) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(isInboxEmpty(dataStreams));
    assertNotEquals(Thread.State.RUNNABLE, getThreadState(dataStreams));
    assertEquals(expectedBuckets, writer.buckets.size());
  }

  private static DDAgentFeaturesDiscovery stubFeatures(boolean supportsDataStreams) {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenReturn(supportsDataStreams);
    return features;
  }

  private static TraceConfig stubTraceConfig(boolean dataStreamsEnabled) {
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(dataStreamsEnabled);
    return traceConfig;
  }

  @TableTest({
    "scenario             | enabledAtAgent | enabledInConfig",
    "agent off, config on | false          | true           ",
    "agent on, config off | true           | false          ",
    "both off             | false          | false          "
  })
  void noPayloadsWrittenIfDataStreamsNotSupportedOrNotEnabled(
      boolean enabledAtAgent, boolean enabledInConfig) throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(enabledAtAgent);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);
    Sink sink = mock(Sink.class);
    TraceConfig traceConfig = stubTraceConfig(enabledInConfig);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    dataStreams.add(
        new StatsPoint(
            DataStreamsTags.create("testType", null, "testTopic", "testGroup", null),
            0,
            0,
            0,
            timeSource.getCurrentTimeNanos(),
            0,
            0,
            0,
            null));
    dataStreams.report();

    awaitIdle(dataStreams);
    verify(payloadWriter, org.mockito.Mockito.never()).writePayload(any(), any());

    // cleanup
    dataStreams.close();
  }

  @Test
  void schemaSamplerSamplesWithCorrectWeights() {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(1000000000000L);
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);
    Sink sink = mock(Sink.class);
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);

    // the first received schema is sampled, with a weight of one.
    assertTrue(dataStreams.canSampleSchema("schema1"));
    assertEquals(1, dataStreams.trySampleSchema("schema1"));
    // the sampling is done by topic, so a schema on a different topic will also be sampled at once,
    // also with a weight of one.
    assertTrue(dataStreams.canSampleSchema("schema2"));
    assertEquals(1, dataStreams.trySampleSchema("schema2"));
    // no time has passed from the last sampling, so the same schema is not sampled again (two times
    // in a row).
    assertFalse(dataStreams.canSampleSchema("schema1"));
    assertFalse(dataStreams.canSampleSchema("schema1"));
    timeSource.advance((long) (30 * 1e9));
    // now, 30 seconds have passed, so the schema is sampled again, with a weight of 3 (so it
    // includes the two times the schema was not sampled).
    assertTrue(dataStreams.canSampleSchema("schema1"));
    assertEquals(3, dataStreams.trySampleSchema("schema1"));
  }

  @Test
  void contextCarrierAdapterTest() {
    CustomContextCarrier carrier = new CustomContextCarrier();
    String keyName = "keyName";
    String keyValue = "keyValue";
    String[] extracted = new String[] {""};

    DataStreamsContextCarrierAdapter.INSTANCE.set(carrier, keyName, keyValue);
    DataStreamsContextCarrierAdapter.INSTANCE.forEachKeyValue(
        carrier,
        (key, value) -> {
          if (keyName.equals(key)) {
            extracted[0] = value;
          }
        });

    assertEquals(keyValue, extracted[0]);
  }

  @Test
  void writeGroupAfterADelay() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  // This test relies on automatic reporting instead of manually calling report
  @Test
  void slowWriteGroupAfterADelay() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    long bucketDuration = TimeUnit.MILLISECONDS.toNanos(200);
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink, features, timeSource, () -> traceConfig, payloadWriter, bucketDuration);
    dataStreams.start();
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(bucketDuration);

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void groupsForCurrentBucketAreNotReported() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.add(new StatsPoint(tags, 3, 4, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void allGroupsWrittenInClose() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags1 =
        DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    DataStreamsTags tags2 =
        DataStreamsTags.create("testType", null, "testTopic2", "testGroup", null);
    dataStreams.add(
        new StatsPoint(tags1, 1, 2, 5, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.add(
        new StatsPoint(tags2, 3, 4, 6, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
    dataStreams.close();

    awaitBuckets(dataStreams, payloadWriter, 2);

    StatsBucket bucket0 = payloadWriter.buckets.get(0);
    assertEquals(1, bucket0.getGroups().size());
    StatsGroup group0 = bucket0.getGroups().iterator().next();
    assertEquals("type:testType", group0.getTags().getType());
    assertEquals("group:testGroup", group0.getTags().getGroup());
    assertEquals("topic:testTopic", group0.getTags().getTopic());
    assertEquals(3, group0.getTags().nonNullSize());
    assertEquals(1L, group0.getHash());
    assertEquals(2L, group0.getParentHash());

    StatsBucket bucket1 = payloadWriter.buckets.get(1);
    assertEquals(1, bucket1.getGroups().size());
    StatsGroup group1 = bucket1.getGroups().iterator().next();
    assertEquals("type:testType", group1.getTags().getType());
    assertEquals("group:testGroup", group1.getTags().getGroup());
    assertEquals("topic:testTopic2", group1.getTags().getTopic());
    assertEquals(3, group1.getTags().nonNullSize());
    assertEquals(3L, group1.getHash());
    assertEquals(4L, group1.getParentHash());

    // cleanup
    payloadWriter.close();
  }

  @Test
  void kafkaOffsetsAreTracked() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    dataStreams.trackBacklog(
        DataStreamsTags.createWithPartition("kafka_commit", "testTopic", "2", null, "testGroup"),
        23);
    dataStreams.trackBacklog(
        DataStreamsTags.createWithPartition("kafka_commit", "testTopic", "2", null, "testGroup"),
        24);
    dataStreams.trackBacklog(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "2", null, null), 23);
    dataStreams.trackBacklog(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic2", "2", null, null), 23);
    dataStreams.trackBacklog(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "2", null, null), 45);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    Collection<Entry<DataStreamsTags, Long>> backlogs = bucket.getBacklogs();
    assertEquals(3, backlogs.size());
    List<Entry<DataStreamsTags, Long>> sortedBacklogs = new ArrayList<>(backlogs);
    sortedBacklogs.sort(Comparator.comparing(e -> e.getKey().toString()));
    assertEquals(
        DataStreamsTags.createWithPartition("kafka_commit", "testTopic", "2", null, "testGroup"),
        sortedBacklogs.get(0).getKey());
    assertEquals(24L, sortedBacklogs.get(0).getValue());
    assertEquals(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "2", null, null),
        sortedBacklogs.get(1).getKey());
    assertEquals(45L, sortedBacklogs.get(1).getValue());
    assertEquals(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic2", "2", null, null),
        sortedBacklogs.get(2).getKey());
    assertEquals(23L, sortedBacklogs.get(2).getValue());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void groupsFromMultipleBucketsAreReported() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags1 =
        DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(
        new StatsPoint(tags1, 1, 2, 5, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS * 10);
    DataStreamsTags tags2 =
        DataStreamsTags.create("testType", null, "testTopic2", "testGroup", null);
    dataStreams.add(
        new StatsPoint(tags2, 3, 4, 6, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 2);

    StatsBucket bucket0 = payloadWriter.buckets.get(0);
    assertEquals(1, bucket0.getGroups().size());
    StatsGroup group0 = bucket0.getGroups().iterator().next();
    assertEquals(3, group0.getTags().nonNullSize());
    assertEquals("type:testType", group0.getTags().getType());
    assertEquals("group:testGroup", group0.getTags().getGroup());
    assertEquals("topic:testTopic", group0.getTags().getTopic());
    assertEquals(1L, group0.getHash());
    assertEquals(2L, group0.getParentHash());

    StatsBucket bucket1 = payloadWriter.buckets.get(1);
    assertEquals(1, bucket1.getGroups().size());
    StatsGroup group1 = bucket1.getGroups().iterator().next();
    assertEquals("type:testType", group1.getTags().getType());
    assertEquals("group:testGroup", group1.getTags().getGroup());
    assertEquals("topic:testTopic2", group1.getTags().getTopic());
    assertEquals(3, group1.getTags().nonNullSize());
    assertEquals(3L, group1.getHash());
    assertEquals(4L, group1.getParentHash());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void multiplePointsAreCorrectlyGroupedInMultipleBuckets() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    dataStreams.add(new StatsPoint(tags, 1, 2, 1, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
    dataStreams.add(
        new StatsPoint(
            tags,
            1,
            2,
            1,
            timeSource.getCurrentTimeNanos(),
            SECONDS.toNanos(10),
            SECONDS.toNanos(10),
            10,
            null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.add(
        new StatsPoint(
            tags,
            1,
            2,
            1,
            timeSource.getCurrentTimeNanos(),
            SECONDS.toNanos(5),
            SECONDS.toNanos(5),
            5,
            null));
    dataStreams.add(
        new StatsPoint(
            tags, 3, 4, 5, timeSource.getCurrentTimeNanos(), SECONDS.toNanos(2), 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 2);

    StatsBucket bucket0 = payloadWriter.buckets.get(0);
    assertEquals(1, bucket0.getGroups().size());
    StatsGroup group0 = bucket0.getGroups().iterator().next();
    assertEquals("type:testType", group0.getTags().getType());
    assertEquals("group:testGroup", group0.getTags().getGroup());
    assertEquals("topic:testTopic", group0.getTags().getTopic());
    assertEquals(3, group0.getTags().nonNullSize());
    assertEquals(1L, group0.getHash());
    assertEquals(2L, group0.getParentHash());
    assertTrue(Math.abs((group0.getPathwayLatency().getMaxValue() - 10) / 10) < 0.01);

    StatsBucket bucket1 = payloadWriter.buckets.get(1);
    assertEquals(2, bucket1.getGroups().size());
    List<StatsGroup> sortedGroups = new ArrayList<>(bucket1.getGroups());
    sortedGroups.sort(Comparator.comparingLong(StatsGroup::getHash));

    StatsGroup sortedGroup0 = sortedGroups.get(0);
    assertEquals(1L, sortedGroup0.getHash());
    assertEquals(2L, sortedGroup0.getParentHash());
    assertEquals("type:testType", sortedGroup0.getTags().getType());
    assertEquals("group:testGroup", sortedGroup0.getTags().getGroup());
    assertEquals("topic:testTopic", sortedGroup0.getTags().getTopic());
    assertEquals(3, sortedGroup0.getTags().nonNullSize());
    assertTrue(Math.abs((sortedGroup0.getPathwayLatency().getMaxValue() - 5) / 5) < 0.01);

    StatsGroup sortedGroup1 = sortedGroups.get(1);
    assertEquals(3L, sortedGroup1.getHash());
    assertEquals(4L, sortedGroup1.getParentHash());
    assertEquals("type:testType", sortedGroup1.getTags().getType());
    assertEquals("group:testGroup", sortedGroup1.getTags().getGroup());
    assertEquals("topic:testTopic", sortedGroup1.getTags().getTopic());
    assertEquals(3, sortedGroup1.getTags().nonNullSize());
    assertTrue(Math.abs((sortedGroup1.getPathwayLatency().getMaxValue() - 2) / 2) < 0.01);

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void featureUpgrade() throws InterruptedException {
    boolean[] supportsDataStreaming = new boolean[] {false};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenAnswer(invocation -> supportsDataStreaming[0]);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    // reporting points when data streams is not supported
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // report called multiple times without advancing past check interval
    dataStreams.report();
    dataStreams.report();
    dataStreams.report();

    // features are not rechecked
    awaitIdle(dataStreams);
    verify(features, org.mockito.Mockito.never()).discover();
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after an upgrade
    supportsDataStreaming[0] = true;
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS);
    dataStreams.report();

    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void featureDowngradeThenUpgrade() throws InterruptedException {
    boolean[] supportsDataStreaming = new boolean[] {true};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenAnswer(invocation -> supportsDataStreaming[0]);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    // reporting points after a downgrade
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    supportsDataStreaming[0] = false;
    dataStreams.onEvent(EventListener.EventType.DOWNGRADED, "");
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after an upgrade
    supportsDataStreaming[0] = true;
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS);
    dataStreams.report();

    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void dynamicConfigEnableAndDisable() throws InterruptedException {
    boolean[] supportsDataStreaming = new boolean[] {true};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenAnswer(invocation -> supportsDataStreaming[0]);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    boolean[] dsmEnabled = new boolean[] {false};
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(invocation -> dsmEnabled[0]);

    // reporting points when data streams is not enabled
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after dynamically enabled
    dsmEnabled[0] = true;
    dataStreams.report();

    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    // disabling data streams dynamically
    dsmEnabled[0] = false;
    dataStreams.report();

    // inbox is processed
    awaitIdle(dataStreams);

    // submitting points after being disabled
    payloadWriter.buckets.clear();

    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are no longer reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void featureAndDynamicConfigUpgradeInteractions() throws InterruptedException {
    boolean[] supportsDataStreaming = new boolean[] {false};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenAnswer(invocation -> supportsDataStreaming[0]);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    boolean[] dsmEnabled = new boolean[] {false};
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(invocation -> dsmEnabled[0]);

    // reporting points when data streams is not supported
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after an upgrade with dsm disabled
    supportsDataStreaming[0] = true;
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS);
    dataStreams.report();

    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are not reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // dsm is enabled dynamically
    dsmEnabled[0] = true;
    dataStreams.report();

    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void moreFeatureAndDynamicConfigUpgradeInteractions() throws InterruptedException {
    boolean[] supportsDataStreaming = new boolean[] {false};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenAnswer(invocation -> supportsDataStreaming[0]);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    boolean[] dsmEnabled = new boolean[] {false};
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(invocation -> dsmEnabled[0]);

    // reporting points when data streams is not supported
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // enabling dsm when not supported by agent
    dsmEnabled[0] = true;
    dataStreams.report();

    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are not reported
    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void schemaRegistryUsagesAreAggregatedByOperation() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    Sink sink = mock(Sink.class);
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();

    // Record serialize and deserialize operations
    dataStreams.reportSchemaRegistryUsage(
        "test-topic", "test-cluster", 123, true, false, "serialize");
    // duplicate serialize
    dataStreams.reportSchemaRegistryUsage(
        "test-topic", "test-cluster", 123, true, false, "serialize");
    dataStreams.reportSchemaRegistryUsage(
        "test-topic", "test-cluster", 123, true, false, "deserialize");
    // different schema/key
    dataStreams.reportSchemaRegistryUsage(
        "test-topic", "test-cluster", 456, true, true, "serialize");

    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    Collection<Entry<StatsBucket.SchemaKey, Long>> usages = bucket.getSchemaRegistryUsages();
    assertEquals(3, usages.size()); // 3 unique combinations

    // Find serialize operation for schema 123 (should have count 2)
    Entry<StatsBucket.SchemaKey, Long> serializeUsage = findUsage(usages, 123, "serialize", false);
    assertNotNull(serializeUsage);
    assertEquals(2L, serializeUsage.getValue()); // Aggregated 2 serialize operations

    // Find deserialize operation for schema 123 (should have count 1)
    Entry<StatsBucket.SchemaKey, Long> deserializeUsage =
        findUsage(usages, 123, "deserialize", false);
    assertNotNull(deserializeUsage);
    assertEquals(1L, deserializeUsage.getValue());

    // Find serialize operation for schema 456 with isKey=true (should have count 1)
    Entry<StatsBucket.SchemaKey, Long> keySerializeUsage =
        findUsage(usages, 456, "serialize", true);
    assertNotNull(keySerializeUsage);
    assertEquals(1L, keySerializeUsage.getValue());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  private static Entry<StatsBucket.SchemaKey, Long> findUsage(
      Collection<Entry<StatsBucket.SchemaKey, Long>> usages,
      int schemaId,
      String operation,
      boolean isKey) {
    for (Entry<StatsBucket.SchemaKey, Long> entry : usages) {
      StatsBucket.SchemaKey key = entry.getKey();
      if (key.getSchemaId() == schemaId
          && operation.equals(key.getOperation())
          && key.isKey() == isKey) {
        return entry;
      }
    }
    return null;
  }

  @Test
  void schemaKeyEqualsAndHashCodeWorkCorrectly() {
    StatsBucket.SchemaKey key1 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize");
    StatsBucket.SchemaKey key2 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize");
    // different topic
    StatsBucket.SchemaKey key3 =
        new StatsBucket.SchemaKey("topic2", "cluster1", 123, true, false, "serialize");
    // different cluster
    StatsBucket.SchemaKey key4 =
        new StatsBucket.SchemaKey("topic1", "cluster2", 123, true, false, "serialize");
    // different schema
    StatsBucket.SchemaKey key5 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 456, true, false, "serialize");
    // different success
    StatsBucket.SchemaKey key6 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, false, false, "serialize");
    // different isKey
    StatsBucket.SchemaKey key7 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, true, "serialize");
    // different operation
    StatsBucket.SchemaKey key8 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "deserialize");

    // Reflexive
    assertEquals(key1, key1);
    assertEquals(key1.hashCode(), key1.hashCode());

    // Symmetric
    assertEquals(key1, key2);
    assertEquals(key2, key1);
    assertEquals(key1.hashCode(), key2.hashCode());

    // Different topic
    assertNotEquals(key1, key3);
    assertNotEquals(key3, key1);

    // Different cluster
    assertNotEquals(key1, key4);
    assertNotEquals(key4, key1);

    // Different schema ID
    assertNotEquals(key1, key5);
    assertNotEquals(key5, key1);

    // Different success
    assertNotEquals(key1, key6);
    assertNotEquals(key6, key1);

    // Different isKey
    assertNotEquals(key1, key7);
    assertNotEquals(key7, key1);

    // Different operation
    assertNotEquals(key1, key8);
    assertNotEquals(key8, key1);

    // Null check
    assertNotEquals(null, key1);

    // Different class
    assertNotEquals("not a schema key", key1);
  }

  @Test
  void statsBucketAggregatesSchemaRegistryUsagesCorrectly() {
    StatsBucket bucket = new StatsBucket(1000L, 10000L);
    SchemaRegistryUsage usage1 =
        new SchemaRegistryUsage("topic1", "cluster1", 123, true, false, "serialize", 1000L, null);
    SchemaRegistryUsage usage2 =
        new SchemaRegistryUsage("topic1", "cluster1", 123, true, false, "serialize", 2000L, null);
    SchemaRegistryUsage usage3 =
        new SchemaRegistryUsage("topic1", "cluster1", 123, true, false, "deserialize", 3000L, null);

    bucket.addSchemaRegistryUsage(usage1);
    // should increment count for same key
    bucket.addSchemaRegistryUsage(usage2);
    // different operation, new key
    bucket.addSchemaRegistryUsage(usage3);

    Collection<Entry<StatsBucket.SchemaKey, Long>> usages = bucket.getSchemaRegistryUsages();
    Map<StatsBucket.SchemaKey, Long> usageMap = new HashMap<>();
    for (Entry<StatsBucket.SchemaKey, Long> entry : usages) {
      usageMap.put(entry.getKey(), entry.getValue());
    }

    assertEquals(2, usages.size());

    // Check serialize count
    StatsBucket.SchemaKey serializeKey =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize");
    assertEquals(2L, usageMap.get(serializeKey));

    // Check deserialize count
    StatsBucket.SchemaKey deserializeKey =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "deserialize");
    assertEquals(1L, usageMap.get(deserializeKey));

    // Check that different operations create different keys
    assertNotEquals(serializeKey, deserializeKey);
  }

  @Test
  void kafkaProducerConfigIsReportedInBucket() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> kafkaConfig = new HashMap<>();
    kafkaConfig.put("bootstrap.servers", "localhost:9092");
    kafkaConfig.put("acks", "all");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", kafkaConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(1, kafkaConfigs.size());
    KafkaConfigReport report = kafkaConfigs.get(0);
    assertEquals("kafka_producer", report.getType());
    assertEquals("localhost:9092", report.getConfig().get("bootstrap.servers"));
    assertEquals("all", report.getConfig().get("acks"));

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void kafkaConsumerConfigIsReportedInBucket() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> kafkaConfig = new HashMap<>();
    kafkaConfig.put("bootstrap.servers", "localhost:9092");
    kafkaConfig.put("group.id", "test-group");
    kafkaConfig.put("auto.offset.reset", "earliest");
    dataStreams.reportKafkaConfig("kafka_consumer", "", "test-group", kafkaConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(1, kafkaConfigs.size());
    KafkaConfigReport report = kafkaConfigs.get(0);
    assertEquals("kafka_consumer", report.getType());
    assertEquals("localhost:9092", report.getConfig().get("bootstrap.servers"));
    assertEquals("test-group", report.getConfig().get("group.id"));
    assertEquals("earliest", report.getConfig().get("auto.offset.reset"));

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void kafkaConsumerGroupMemberIsReportedInBucket() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    dataStreams.reportKafkaConsumerGroupMember(
        "cluster-1", "test-group", "consumer-1-abc123", 7, "range");
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(1, kafkaConfigs.size());
    KafkaConfigReport report = kafkaConfigs.get(0);
    assertEquals("kafka_consumer", report.getType());
    assertEquals("cluster-1", report.getKafkaClusterId());
    assertEquals("test-group", report.getConsumerGroup());
    assertEquals("consumer-1-abc123", report.getMemberId());
    assertEquals(7, report.getGenerationId());
    assertEquals("range", report.getMemberProtocol());
    assertTrue(report.getConfig().isEmpty());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void duplicateKafkaConfigsAreEachReportedInTheBucket() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    // reporting the same config twice
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> config1 = new HashMap<>();
    config1.put("bootstrap.servers", "localhost:9092");
    config1.put("acks", "all");
    Map<String, String> config2 = new HashMap<>();
    config2.put("bootstrap.servers", "localhost:9092");
    config2.put("acks", "all");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config1);
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config2);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // both configs are reported in the bucket
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(2, kafkaConfigs.size());
    for (KafkaConfigReport report : kafkaConfigs) {
      assertEquals("kafka_producer", report.getType());
      assertEquals("localhost:9092", report.getConfig().get("bootstrap.servers"));
      assertEquals("all", report.getConfig().get("acks"));
    }

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void kafkaConfigsReportedInSeparateBucketsAppearInEachBucket() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    // reporting a config in the first bucket
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> kafkaConfig = new HashMap<>();
    kafkaConfig.put("bootstrap.servers", "localhost:9092");
    kafkaConfig.put("acks", "all");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", kafkaConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // first bucket has the config
    awaitBuckets(dataStreams, payloadWriter, 1);
    assertEquals(1, payloadWriter.buckets.get(0).getKafkaConfigs().size());

    // reporting the same config again in a new bucket
    payloadWriter.buckets.clear();
    dataStreams.reportKafkaConfig("kafka_producer", "", "", kafkaConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // second bucket also has the config
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(1, kafkaConfigs.size());
    KafkaConfigReport report = kafkaConfigs.get(0);
    assertEquals("kafka_producer", report.getType());
    assertEquals("localhost:9092", report.getConfig().get("bootstrap.servers"));
    assertEquals("all", report.getConfig().get("acks"));

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void differentKafkaConfigsAreBothReported() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    // reporting producer and consumer configs
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> producerConfig = new HashMap<>();
    producerConfig.put("bootstrap.servers", "localhost:9092");
    producerConfig.put("acks", "all");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", producerConfig);
    Map<String, String> consumerConfig = new HashMap<>();
    consumerConfig.put("bootstrap.servers", "localhost:9092");
    consumerConfig.put("group.id", "my-group");
    dataStreams.reportKafkaConfig("kafka_consumer", "", "my-group", consumerConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // both configs are reported
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(2, kafkaConfigs.size());

    KafkaConfigReport producerReport = findReport(kafkaConfigs, "kafka_producer");
    assertNotNull(producerReport);
    assertEquals("localhost:9092", producerReport.getConfig().get("bootstrap.servers"));
    assertEquals("all", producerReport.getConfig().get("acks"));

    KafkaConfigReport consumerReport = findReport(kafkaConfigs, "kafka_consumer");
    assertNotNull(consumerReport);
    assertEquals("localhost:9092", consumerReport.getConfig().get("bootstrap.servers"));
    assertEquals("my-group", consumerReport.getConfig().get("group.id"));

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  private static KafkaConfigReport findReport(List<KafkaConfigReport> configs, String type) {
    for (KafkaConfigReport report : configs) {
      if (type.equals(report.getType())) {
        return report;
      }
    }
    return null;
  }

  @Test
  void kafkaConfigsWithDifferentValuesForSameTypeAreNotDeduplicated() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    // reporting two producer configs with different settings
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> config1 = new HashMap<>();
    config1.put("bootstrap.servers", "localhost:9092");
    config1.put("acks", "all");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config1);
    Map<String, String> config2 = new HashMap<>();
    config2.put("bootstrap.servers", "localhost:9093");
    config2.put("acks", "1");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config2);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // both configs are reported because they have different values
    awaitBuckets(dataStreams, payloadWriter, 1);
    assertEquals(2, payloadWriter.buckets.get(0).getKafkaConfigs().size());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void kafkaConfigsAreReportedAlongsideStatsPoints() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    // reporting both stats points and kafka configs
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tags = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tags, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    Map<String, String> kafkaConfig = new HashMap<>();
    kafkaConfig.put("bootstrap.servers", "localhost:9092");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", kafkaConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // bucket contains both stats groups and kafka configs
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(1, kafkaConfigs.size());

    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals(1L, group.getHash());
    assertEquals(2L, group.getParentHash());

    KafkaConfigReport report = kafkaConfigs.get(0);
    assertEquals("kafka_producer", report.getType());
    assertEquals("localhost:9092", report.getConfig().get("bootstrap.servers"));

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @TableTest({
    "scenario             | enabledAtAgent | enabledInConfig",
    "agent off, config on | false          | true           ",
    "agent on, config off | true           | false          ",
    "both off             | false          | false          "
  })
  void kafkaConfigsNotReportedWhenDSMIsDisabled(boolean enabledAtAgent, boolean enabledInConfig)
      throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(enabledAtAgent);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    Sink sink = mock(Sink.class);
    TraceConfig traceConfig = stubTraceConfig(enabledInConfig);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> kafkaConfig = new HashMap<>();
    kafkaConfig.put("bootstrap.servers", "localhost:9092");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", kafkaConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitIdle(dataStreams);
    assertTrue(payloadWriter.buckets.isEmpty());

    // cleanup
    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void kafkaConfigsFlushedOnClose() throws InterruptedException {
    DDAgentFeaturesDiscovery features = stubFeatures(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    TraceConfig traceConfig = stubTraceConfig(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    Map<String, String> kafkaConfig = new HashMap<>();
    kafkaConfig.put("bootstrap.servers", "localhost:9092");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", kafkaConfig);
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
    dataStreams.close();

    // configs in the current bucket are flushed on close
    awaitBuckets(dataStreams, payloadWriter, 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    List<KafkaConfigReport> kafkaConfigs = bucket.getKafkaConfigs();
    assertEquals(1, kafkaConfigs.size());
    KafkaConfigReport report = kafkaConfigs.get(0);
    assertEquals("kafka_producer", report.getType());
    assertEquals("localhost:9092", report.getConfig().get("bootstrap.servers"));

    // cleanup
    payloadWriter.close();
  }

  @Test
  void kafkaConfigReportEqualsAndHashCodeWorkCorrectly() {
    Map<String, String> sameConfig = new HashMap<>();
    sameConfig.put("bootstrap.servers", "localhost:9092");
    sameConfig.put("acks", "all");
    Map<String, String> sameConfig2 = new HashMap<>();
    sameConfig2.put("bootstrap.servers", "localhost:9092");
    sameConfig2.put("acks", "all");
    Map<String, String> differentConfig = new HashMap<>();
    differentConfig.put("bootstrap.servers", "localhost:9093");

    KafkaConfigReport config1 =
        new KafkaConfigReport("kafka_producer", "", "", sameConfig, 1000L, null);
    // different timestamp
    KafkaConfigReport config2 =
        new KafkaConfigReport("kafka_producer", "", "", sameConfig2, 2000L, null);
    // different type
    KafkaConfigReport config3 =
        new KafkaConfigReport("kafka_consumer", "", "", sameConfig, 1000L, null);
    // different config values
    KafkaConfigReport config4 =
        new KafkaConfigReport("kafka_producer", "", "", differentConfig, 1000L, null);
    // different serviceNameOverride
    KafkaConfigReport config5 =
        new KafkaConfigReport("kafka_producer", "", "", sameConfig, 1000L, "other-service");

    // Reflexive
    assertEquals(config1, config1);
    assertEquals(config1.hashCode(), config1.hashCode());

    // Same type and config, different timestamp -- equals (timestamp is NOT part of equals)
    assertEquals(config1, config2);
    assertEquals(config2, config1);
    assertEquals(config1.hashCode(), config2.hashCode());

    // Same type and config, different serviceNameOverride -- equals (serviceNameOverride is NOT
    // part of equals)
    assertEquals(config1, config5);
    assertEquals(config5, config1);
    assertEquals(config1.hashCode(), config5.hashCode());

    // Different type
    assertNotEquals(config1, config3);
    assertNotEquals(config3, config1);

    // Different config values
    assertNotEquals(config1, config4);
    assertNotEquals(config4, config1);

    // Null check
    assertNotEquals(null, config1);

    // Different class
    assertNotEquals("not a config report", config1);
  }

  @Test
  void statsBucketStoresKafkaConfigs() {
    StatsBucket bucket = new StatsBucket(1000L, 10000L);
    Map<String, String> producerCfg = new HashMap<>();
    producerCfg.put("acks", "all");
    KafkaConfigReport config1 =
        new KafkaConfigReport("kafka_producer", "", "", producerCfg, 1000L, null);
    Map<String, String> consumerCfg = new HashMap<>();
    consumerCfg.put("group.id", "test");
    KafkaConfigReport config2 =
        new KafkaConfigReport("kafka_consumer", "", "test", consumerCfg, 2000L, null);

    bucket.addKafkaConfig(config1);
    bucket.addKafkaConfig(config2);

    assertEquals(2, bucket.getKafkaConfigs().size());
    assertEquals("kafka_producer", bucket.getKafkaConfigs().get(0).getType());
    assertEquals("all", bucket.getKafkaConfigs().get(0).getConfig().get("acks"));
    assertEquals("kafka_consumer", bucket.getKafkaConfigs().get(1).getType());
    assertEquals("test", bucket.getKafkaConfigs().get(1).getConfig().get("group.id"));
  }

  static class CapturingPayloadWriter implements DatastreamsPayloadWriter {
    boolean accepting = true;
    final List<StatsBucket> buckets = new ArrayList<>();

    @Override
    public void writePayload(Collection<StatsBucket> payload, String serviceNameOverride) {
      if (accepting) {
        buckets.addAll(payload);
      }
    }

    void close() {
      // Stop accepting new buckets so any late submissions by the reporting thread aren't seen
      accepting = false;
    }
  }

  private DefaultDataStreamsMonitoring newDataStreamsMonitoring() {
    return new DefaultDataStreamsMonitoring(
        mock(Sink.class),
        stubFeatures(true),
        new ControllableTimeSource(),
        () -> stubTraceConfig(true),
        mock(DatastreamsPayloadWriter.class),
        DEFAULT_BUCKET_DURATION_NANOS);
  }

  @Test
  void setCheckpointTagsTheSpanWithThePathwayHash() {
    DefaultDataStreamsMonitoring dataStreams = newDataStreamsMonitoring();

    // Negative so the signed and unsigned string representations differ, proving the tag uses
    // Long.toUnsignedString (pathway hashes are unsigned 64-bit values).
    long hash = -1234567890123456789L;
    PathwayContext pathwayContext = mock(PathwayContext.class);
    when(pathwayContext.getHash()).thenReturn(hash);
    AgentSpanContext spanContext = mock(AgentSpanContext.class);
    when(spanContext.getPathwayContext()).thenReturn(pathwayContext);
    AgentSpan span = mock(AgentSpan.class);
    when(span.spanContext()).thenReturn(spanContext);
    DataStreamsContext context = mock(DataStreamsContext.class);

    dataStreams.setCheckpoint(span, context);

    verify(pathwayContext).setCheckpoint(eq(context), any());
    verify(span).setTag(DDTags.PATHWAY_HASH, Long.toUnsignedString(hash));
  }

  @Test
  void setCheckpointDoesNotTagTheSpanWhenThePathwayHashIsZero() {
    DefaultDataStreamsMonitoring dataStreams = newDataStreamsMonitoring();

    PathwayContext pathwayContext = mock(PathwayContext.class);
    when(pathwayContext.getHash()).thenReturn(0L);
    AgentSpanContext spanContext = mock(AgentSpanContext.class);
    when(spanContext.getPathwayContext()).thenReturn(pathwayContext);
    AgentSpan span = mock(AgentSpan.class);
    when(span.spanContext()).thenReturn(spanContext);

    dataStreams.setCheckpoint(span, mock(DataStreamsContext.class));

    verify(span, never()).setTag(eq(DDTags.PATHWAY_HASH), any(String.class));
  }

  @Test
  void setCheckpointDoesNotTagTheSpanWhenThereIsNoPathwayContext() {
    DefaultDataStreamsMonitoring dataStreams = newDataStreamsMonitoring();

    AgentSpanContext spanContext = mock(AgentSpanContext.class);
    when(spanContext.getPathwayContext()).thenReturn(null);
    AgentSpan span = mock(AgentSpan.class);
    when(span.spanContext()).thenReturn(spanContext);

    dataStreams.setCheckpoint(span, mock(DataStreamsContext.class));

    verify(span, never()).setTag(eq(DDTags.PATHWAY_HASH), any(String.class));
  }

  static class CustomContextCarrier implements DataStreamsContextCarrier {
    private final Map<String, Object> data = new HashMap<>();

    @Override
    public Set<Entry<String, Object>> entries() {
      return data.entrySet();
    }

    @Override
    public void set(String key, String value) {
      data.put(key, value);
    }
  }
}

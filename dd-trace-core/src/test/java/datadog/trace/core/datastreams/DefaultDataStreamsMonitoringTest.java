package datadog.trace.core.datastreams;

import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoring.FEATURE_CHECK_INTERVAL_NANOS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.SchemaRegistryUsage;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DefaultDataStreamsMonitoringTest extends DDCoreSpecification {
  static final long DEFAULT_BUCKET_DURATION_NANOS =
      Config.get().getDataStreamsBucketDurationNanoseconds();

  private static void awaitCondition(long timeoutMillis, ConditionChecker condition)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (condition.check()) {
        return;
      }
      Thread.sleep(50);
    }
    assertTrue(condition.check(), "Condition not met within timeout");
  }

  @FunctionalInterface
  interface ConditionChecker {
    boolean check() throws Exception;
  }

  static Stream<Arguments> noPayloadsWrittenArguments() {
    return Stream.of(
        Arguments.of(false, true), Arguments.of(true, false), Arguments.of(false, false));
  }

  @ParameterizedTest(
      name = "[{index}] No payloads written if data streams not supported or not enabled")
  @MethodSource("noPayloadsWrittenArguments")
  void noPayloadsWrittenIfDataStreamsNotSupportedOrNotEnabled(
      boolean enabledAtAgent, boolean enabledInConfig) throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(enabledAtAgent);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);
    Sink sink = mock(Sink.class);

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(enabledInConfig);

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

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty() && dataStreams.thread.getState() != Thread.State.RUNNABLE);

    dataStreams.close();
  }

  @Test
  void schemaSamplerSamplesWithCorrectWeights() {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set((long) 1e12);
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);
    Sink sink = mock(Sink.class);
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

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
    // now, 30 seconds have passed, so the schema is sampled again, with a weight of 3
    assertTrue(dataStreams.canSampleSchema("schema1"));
    assertEquals(3, dataStreams.trySampleSchema("schema1"));
  }

  @Test
  void contextCarrierAdapterTest() {
    CustomContextCarrier carrier = new CustomContextCarrier();
    String keyName = "keyName";
    String keyValue = "keyValue";
    String[] extracted = {""};

    DataStreamsContextCarrierAdapter.INSTANCE.set(carrier, keyName, keyValue);
    DataStreamsContextCarrierAdapter.INSTANCE.forEachKeyValue(
        carrier,
        (key, value) -> {
          if (key.equals(keyName)) {
            extracted[0] = value;
          }
        });

    assertEquals(keyValue, extracted[0]);
  }

  @Test
  void writeGroupAfterADelay() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1, group.getHash());
    assertEquals(2, group.getParentHash());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void slowWriteGroupAfterADelay() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    long bucketDuration = TimeUnit.MILLISECONDS.toNanos(200);

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink, features, timeSource, () -> traceConfig, payloadWriter, bucketDuration);
    dataStreams.start();
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(bucketDuration);

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1, group.getHash());
    assertEquals(2, group.getParentHash());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void groupsForCurrentBucketAreNotReported() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.add(new StatsPoint(tg, 3, 4, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
    dataStreams.report();

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(1, bucket.getGroups().size());
    StatsGroup group = bucket.getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1, group.getHash());
    assertEquals(2, group.getParentHash());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void allGroupsWrittenInClose() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    DataStreamsTags tg2 = DataStreamsTags.create("testType", null, "testTopic2", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 5, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.add(new StatsPoint(tg2, 3, 4, 6, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
    dataStreams.close();

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 2);

    assertEquals(2, payloadWriter.buckets.size());

    StatsBucket bucket0 = payloadWriter.buckets.get(0);
    assertEquals(1, bucket0.getGroups().size());
    StatsGroup group0 = bucket0.getGroups().iterator().next();
    assertEquals("type:testType", group0.getTags().getType());
    assertEquals("group:testGroup", group0.getTags().getGroup());
    assertEquals("topic:testTopic", group0.getTags().getTopic());
    assertEquals(3, group0.getTags().nonNullSize());
    assertEquals(1, group0.getHash());
    assertEquals(2, group0.getParentHash());

    StatsBucket bucket1 = payloadWriter.buckets.get(1);
    assertEquals(1, bucket1.getGroups().size());
    StatsGroup group1 = bucket1.getGroups().iterator().next();
    assertEquals("type:testType", group1.getTags().getType());
    assertEquals("group:testGroup", group1.getTags().getGroup());
    assertEquals("topic:testTopic2", group1.getTags().getTopic());
    assertEquals(3, group1.getTags().nonNullSize());
    assertEquals(3, group1.getHash());
    assertEquals(4, group1.getParentHash());

    payloadWriter.close();
  }

  @Test
  void kafkaOffsetsAreTracked() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

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

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(3, bucket.getBacklogs().size());
    List<Map.Entry<DataStreamsTags, Long>> list = new ArrayList<>(bucket.getBacklogs());
    list.sort(Comparator.comparing(e -> e.getKey().toString()));

    assertEquals(
        DataStreamsTags.createWithPartition("kafka_commit", "testTopic", "2", null, "testGroup"),
        list.get(0).getKey());
    assertEquals(24L, (long) list.get(0).getValue());
    assertEquals(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "2", null, null),
        list.get(1).getKey());
    assertEquals(45L, (long) list.get(1).getValue());
    assertEquals(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic2", "2", null, null),
        list.get(2).getKey());
    assertEquals(23L, (long) list.get(2).getValue());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void groupsFromMultipleBucketsAreReported() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 5, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS * 10);
    DataStreamsTags tg2 = DataStreamsTags.create("testType", null, "testTopic2", "testGroup", null);
    dataStreams.add(new StatsPoint(tg2, 3, 4, 6, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 2);

    StatsBucket bucket0 = payloadWriter.buckets.get(0);
    assertEquals(1, bucket0.getGroups().size());
    StatsGroup group0 = bucket0.getGroups().iterator().next();
    assertEquals(3, group0.getTags().nonNullSize());
    assertEquals("type:testType", group0.getTags().getType());
    assertEquals("group:testGroup", group0.getTags().getGroup());
    assertEquals("topic:testTopic", group0.getTags().getTopic());
    assertEquals(1, group0.getHash());
    assertEquals(2, group0.getParentHash());

    StatsBucket bucket1 = payloadWriter.buckets.get(1);
    assertEquals(1, bucket1.getGroups().size());
    StatsGroup group1 = bucket1.getGroups().iterator().next();
    assertEquals("type:testType", group1.getTags().getType());
    assertEquals("group:testGroup", group1.getTags().getGroup());
    assertEquals("topic:testTopic2", group1.getTags().getTopic());
    assertEquals(3, group1.getTags().nonNullSize());
    assertEquals(3, group1.getHash());
    assertEquals(4, group1.getParentHash());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void multiplePointsAreCorrectlyGroupedInMultipleBuckets() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    dataStreams.add(new StatsPoint(tg, 1, 2, 1, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
    dataStreams.add(
        new StatsPoint(
            tg,
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
            tg,
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
            tg, 3, 4, 5, timeSource.getCurrentTimeNanos(), SECONDS.toNanos(2), 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 2);

    StatsBucket bucket0 = payloadWriter.buckets.get(0);
    assertEquals(1, bucket0.getGroups().size());
    StatsGroup group0 = bucket0.getGroups().iterator().next();
    assertEquals("type:testType", group0.getTags().getType());
    assertEquals("group:testGroup", group0.getTags().getGroup());
    assertEquals("topic:testTopic", group0.getTags().getTopic());
    assertEquals(3, group0.getTags().nonNullSize());
    assertEquals(1, group0.getHash());
    assertEquals(2, group0.getParentHash());
    assertTrue(Math.abs((group0.getPathwayLatency().getMaxValue() - 10) / 10.0) < 0.01);

    StatsBucket bucket1 = payloadWriter.buckets.get(1);
    assertEquals(2, bucket1.getGroups().size());
    List<StatsGroup> sortedGroups = new ArrayList<>(bucket1.getGroups());
    sortedGroups.sort(Comparator.comparingLong(StatsGroup::getHash));

    StatsGroup sg0 = sortedGroups.get(0);
    assertEquals(1, sg0.getHash());
    assertEquals(2, sg0.getParentHash());
    assertEquals("type:testType", sg0.getTags().getType());
    assertEquals("group:testGroup", sg0.getTags().getGroup());
    assertEquals("topic:testTopic", sg0.getTags().getTopic());
    assertEquals(3, sg0.getTags().nonNullSize());
    assertTrue(Math.abs((sg0.getPathwayLatency().getMaxValue() - 5) / 5.0) < 0.01);

    StatsGroup sg1 = sortedGroups.get(1);
    assertEquals(3, sg1.getHash());
    assertEquals(4, sg1.getParentHash());
    assertEquals("type:testType", sg1.getTags().getType());
    assertEquals("group:testGroup", sg1.getTags().getGroup());
    assertEquals("topic:testTopic", sg1.getTags().getTopic());
    assertEquals(3, sg1.getTags().nonNullSize());
    assertTrue(Math.abs((sg1.getPathwayLatency().getMaxValue() - 2) / 2.0) < 0.01);

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void featureUpgrade() throws Exception {
    boolean[] supportsDataStreaming = {false};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenAnswer(inv -> supportsDataStreaming[0]);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

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
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty() && dataStreams.thread.getState() != Thread.State.RUNNABLE);
    assertTrue(payloadWriter.buckets.isEmpty());

    // report called multiple times without advancing past check interval
    dataStreams.report();
    dataStreams.report();
    dataStreams.report();

    // features are not rechecked
    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty() && dataStreams.thread.getState() != Thread.State.RUNNABLE);
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after an upgrade
    supportsDataStreaming[0] = true;
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS);
    dataStreams.report();

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty() && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsGroup group = payloadWriter.buckets.get(0).getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1, group.getHash());
    assertEquals(2, group.getParentHash());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void featureDowngradeThenUpgrade() throws Exception {
    boolean[] supportsDataStreaming = {true};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenAnswer(inv -> supportsDataStreaming[0]);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

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
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty() && dataStreams.thread.getState() != Thread.State.RUNNABLE);
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after an upgrade
    supportsDataStreaming[0] = true;
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS);
    dataStreams.report();

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty() && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsGroup group = payloadWriter.buckets.get(0).getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1, group.getHash());
    assertEquals(2, group.getParentHash());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void dynamicConfigEnableAndDisable() throws Exception {
    boolean[] supportsDataStreaming = {true};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenAnswer(inv -> supportsDataStreaming[0]);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    boolean[] dsmEnabled = {false};
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(inv -> dsmEnabled[0]);

    // reporting points when data streams is not enabled
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            features,
            timeSource,
            () -> traceConfig,
            payloadWriter,
            DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.start();
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty() && dataStreams.thread.getState() != Thread.State.RUNNABLE);
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after dynamically enabled
    dsmEnabled[0] = true;
    dataStreams.report();

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty() && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsGroup group = payloadWriter.buckets.get(0).getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1, group.getHash());
    assertEquals(2, group.getParentHash());

    // disabling data streams dynamically
    dsmEnabled[0] = false;
    dataStreams.report();

    // inbox is processed
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty());

    // submitting points after being disabled
    payloadWriter.buckets.clear();

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are no longer reported
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty() && payloadWriter.buckets.isEmpty());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void featureAndDynamicConfigUpgradeInteractions() throws Exception {
    boolean[] supportsDataStreaming = {false};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenAnswer(inv -> supportsDataStreaming[0]);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    boolean[] dsmEnabled = {false};
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(inv -> dsmEnabled[0]);

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
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty() && dataStreams.thread.getState() != Thread.State.RUNNABLE);
    assertTrue(payloadWriter.buckets.isEmpty());

    // submitting points after an upgrade with dsm disabled
    supportsDataStreaming[0] = true;
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS);
    dataStreams.report();

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are not reported
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty() && payloadWriter.buckets.isEmpty());

    // dsm is enabled dynamically
    dsmEnabled[0] = true;
    dataStreams.report();

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are now reported
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty() && payloadWriter.buckets.size() == 1);

    assertEquals(1, payloadWriter.buckets.size());
    StatsGroup group = payloadWriter.buckets.get(0).getGroups().iterator().next();
    assertEquals("type:testType", group.getTags().getType());
    assertEquals("group:testGroup", group.getTags().getGroup());
    assertEquals("topic:testTopic", group.getTags().getTopic());
    assertEquals(3, group.getTags().nonNullSize());
    assertEquals(1, group.getHash());
    assertEquals(2, group.getParentHash());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void moreFeatureAndDynamicConfigUpgradeInteractions() throws Exception {
    boolean[] supportsDataStreaming = {false};
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenAnswer(inv -> supportsDataStreaming[0]);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    Sink sink = mock(Sink.class);
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();

    boolean[] dsmEnabled = {false};
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(inv -> dsmEnabled[0]);

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
    DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // no buckets are reported
    awaitCondition(
        1000,
        () ->
            dataStreams.inbox.isEmpty() && dataStreams.thread.getState() != Thread.State.RUNNABLE);
    assertTrue(payloadWriter.buckets.isEmpty());

    // enabling dsm when not supported by agent
    dsmEnabled[0] = true;
    dataStreams.report();

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    // points are not reported
    awaitCondition(1000, () -> dataStreams.inbox.isEmpty() && payloadWriter.buckets.isEmpty());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void schemaRegistryUsagesAreAggregatedByOperation() throws Exception {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    CapturingPayloadWriter payloadWriter = new CapturingPayloadWriter();
    Sink sink = mock(Sink.class);
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

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
    dataStreams.reportSchemaRegistryUsage(
        "test-topic", "test-cluster", 123, true, false, "serialize"); // duplicate serialize
    dataStreams.reportSchemaRegistryUsage(
        "test-topic", "test-cluster", 123, true, false, "deserialize");
    dataStreams.reportSchemaRegistryUsage(
        "test-topic", "test-cluster", 456, true, true, "serialize"); // different schema/key

    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    dataStreams.report();

    awaitCondition(
        2000,
        () ->
            dataStreams.inbox.isEmpty()
                && dataStreams.thread.getState() != Thread.State.RUNNABLE
                && payloadWriter.buckets.size() == 1);

    StatsBucket bucket = payloadWriter.buckets.get(0);
    assertEquals(3, bucket.getSchemaRegistryUsages().size()); // 3 unique combinations

    // Find serialize operation for schema 123 (should have count 2)
    Map.Entry<StatsBucket.SchemaKey, Long> serializeUsage =
        bucket.getSchemaRegistryUsages().stream()
            .filter(
                e ->
                    e.getKey().getSchemaId() == 123
                        && "serialize".equals(e.getKey().getOperation())
                        && !e.getKey().isKey())
            .findFirst()
            .orElse(null);
    assertNotNull(serializeUsage);
    assertEquals(2L, (long) serializeUsage.getValue()); // Aggregated 2 serialize operations

    // Find deserialize operation for schema 123 (should have count 1)
    Map.Entry<StatsBucket.SchemaKey, Long> deserializeUsage =
        bucket.getSchemaRegistryUsages().stream()
            .filter(
                e ->
                    e.getKey().getSchemaId() == 123
                        && "deserialize".equals(e.getKey().getOperation())
                        && !e.getKey().isKey())
            .findFirst()
            .orElse(null);
    assertNotNull(deserializeUsage);
    assertEquals(1L, (long) deserializeUsage.getValue());

    // Find serialize operation for schema 456 with isKey=true (should have count 1)
    Map.Entry<StatsBucket.SchemaKey, Long> keySerializeUsage =
        bucket.getSchemaRegistryUsages().stream()
            .filter(
                e ->
                    e.getKey().getSchemaId() == 456
                        && "serialize".equals(e.getKey().getOperation())
                        && e.getKey().isKey())
            .findFirst()
            .orElse(null);
    assertNotNull(keySerializeUsage);
    assertEquals(1L, (long) keySerializeUsage.getValue());

    payloadWriter.close();
    dataStreams.close();
  }

  @Test
  void schemaKeyEqualsAndHashCodeWorkCorrectly() {
    StatsBucket.SchemaKey key1 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize");
    StatsBucket.SchemaKey key2 =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize");
    StatsBucket.SchemaKey key3 =
        new StatsBucket.SchemaKey(
            "topic2", "cluster1", 123, true, false, "serialize"); // different topic
    StatsBucket.SchemaKey key4 =
        new StatsBucket.SchemaKey(
            "topic1", "cluster2", 123, true, false, "serialize"); // different cluster
    StatsBucket.SchemaKey key5 =
        new StatsBucket.SchemaKey(
            "topic1", "cluster1", 456, true, false, "serialize"); // different schema
    StatsBucket.SchemaKey key6 =
        new StatsBucket.SchemaKey(
            "topic1", "cluster1", 123, false, false, "serialize"); // different success
    StatsBucket.SchemaKey key7 =
        new StatsBucket.SchemaKey(
            "topic1", "cluster1", 123, true, true, "serialize"); // different isKey
    StatsBucket.SchemaKey key8 =
        new StatsBucket.SchemaKey(
            "topic1", "cluster1", 123, true, false, "deserialize"); // different operation

    // Reflexive
    assertTrue(key1.equals(key1));
    assertEquals(key1.hashCode(), key1.hashCode());

    // Symmetric
    assertTrue(key1.equals(key2));
    assertTrue(key2.equals(key1));
    assertEquals(key1.hashCode(), key2.hashCode());

    // Different topic
    assertFalse(key1.equals(key3));
    assertFalse(key3.equals(key1));

    // Different cluster
    assertFalse(key1.equals(key4));
    assertFalse(key4.equals(key1));

    // Different schema ID
    assertFalse(key1.equals(key5));
    assertFalse(key5.equals(key1));

    // Different success
    assertFalse(key1.equals(key6));
    assertFalse(key6.equals(key1));

    // Different isKey
    assertFalse(key1.equals(key7));
    assertFalse(key7.equals(key1));

    // Different operation
    assertFalse(key1.equals(key8));
    assertFalse(key8.equals(key1));

    // Null check
    assertFalse(key1.equals(null));

    // Different class
    assertFalse(key1.equals("not a schema key"));
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
    bucket.addSchemaRegistryUsage(usage2); // should increment count for same key
    bucket.addSchemaRegistryUsage(usage3); // different operation, new key

    List<Map.Entry<StatsBucket.SchemaKey, Long>> usages =
        new ArrayList<>(bucket.getSchemaRegistryUsages());
    Map<StatsBucket.SchemaKey, Long> usageMap = new HashMap<>();
    for (Map.Entry<StatsBucket.SchemaKey, Long> e : usages) {
      usageMap.put(e.getKey(), e.getValue());
    }

    assertEquals(2, usages.size());

    // Check serialize count
    StatsBucket.SchemaKey serializeKey =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize");
    assertEquals(2L, (long) usageMap.get(serializeKey));

    // Check deserialize count
    StatsBucket.SchemaKey deserializeKey =
        new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "deserialize");
    assertEquals(1L, (long) usageMap.get(deserializeKey));

    // Check that different operations create different keys
    assertNotEquals(serializeKey, deserializeKey);
  }
}

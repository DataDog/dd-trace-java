package datadog.trace.core.datastreams;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsContext.fromTags;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoring.FEATURE_CHECK_INTERVAL_NANOS;
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoring.LITE_ERROR_RESET_INTERVAL_NANOS;
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoringTestBridge.getSeenSet;
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoringTestBridge.getThreadState;
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoringTestBridge.isInboxEmpty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.DDCoreJavaSpecification;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataStreamsLiteTest extends DDCoreJavaSpecification {
  private static final long BUCKET_DURATION_NANOS =
      Config.get().getDataStreamsBucketDurationNanoseconds();
  private static final DataStreamsTags PRODUCE =
      DataStreamsTags.create("kafka", OUTBOUND, "orders");

  private final ControllableTimeSource timeSource = new ControllableTimeSource();
  private final RecordingPayloadWriter payloadWriter = new RecordingPayloadWriter();
  private final List<StatsPoint> queued = new CopyOnWriteArrayList<>();
  private DDAgentFeaturesDiscovery features = stubFeatures(true);
  private DefaultDataStreamsMonitoring dataStreams;

  @BeforeAll
  static void registerHistograms() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @AfterEach
  void closeDataStreams() {
    if (dataStreams != null) {
      dataStreams.close();
    }
  }

  @Test
  void samePathwayCheckpointedManyTimesIsQueuedOnce() {
    startLiteDataStreams();

    for (int i = 0; i < 1000; i++) {
      dataStreams.newPathwayContext().setCheckpoint(fromTags(PRODUCE), this::add);
    }

    assertEquals(1, queued.size());
  }

  @Test
  void eachDistinctPathwayIsQueuedOnce() {
    startLiteDataStreams();

    for (int repeat = 0; repeat < 3; repeat++) {
      for (int i = 0; i < 100; i++) {
        DataStreamsTags tags =
            DataStreamsTags.create("kafka", INBOUND, "topic-" + i, "group", null);
        dataStreams.newPathwayContext().setCheckpoint(fromTags(tags), this::add);
      }
      // same topic, another consumer group
      DataStreamsTags otherGroup =
          DataStreamsTags.create("kafka", INBOUND, "topic-0", "other-group", null);
      dataStreams.newPathwayContext().setCheckpoint(fromTags(otherGroup), this::add);
    }

    assertEquals(101, queued.size());
  }

  @Test
  void pathwayIsReportedLaterWhenItsPointCouldNotBeQueued() throws InterruptedException {
    dataStreams = liteDataStreams();
    PathwaySeenSet seenSet = getSeenSet(dataStreams);

    // not started yet, so nothing can be queued
    new DefaultPathwayContext(timeSource, null, seenSet)
        .setCheckpoint(fromTags(PRODUCE), this::add);
    assertTrue(queued.isEmpty());

    dataStreams.start();
    new DefaultPathwayContext(timeSource, null, seenSet)
        .setCheckpoint(fromTags(PRODUCE), this::add);
    new DefaultPathwayContext(timeSource, null, seenSet)
        .setCheckpoint(fromTags(PRODUCE), this::add);
    assertEquals(1, queued.size());

    timeSource.advance(BUCKET_DURATION_NANOS);
    dataStreams.report();
    awaitPayloads(1);
    assertEquals(1, payloadWriter.groups().size());
  }

  @Test
  void pathwayDroppedWhileTheAgentLacksDataStreamsIsReportedAfterTheAgentUpgrades()
      throws InterruptedException {
    features = stubFeatures(false);
    startLiteDataStreams();
    checkpoint(PRODUCE);
    awaitIdle();
    checkpoint(PRODUCE);
    assertEquals(1, queued.size());

    when(features.supportsDataStreams()).thenReturn(true);
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS);
    dataStreams.report();
    awaitIdle();
    checkpoint(PRODUCE);
    assertEquals(2, queued.size());

    timeSource.advance(BUCKET_DURATION_NANOS);
    dataStreams.report();
    awaitPayloads(1);
    assertEquals(1, payloadWriter.groups().size());
  }

  @Test
  void pathwaysAreReportedAgainInEachEpoch() throws InterruptedException {
    startLiteDataStreams();
    checkpoint(PRODUCE);
    checkpoint(PRODUCE);
    assertEquals(1, queued.size());

    // the first epoch lasts the refresh interval +/- 20%
    timeSource.advance(SECONDS.toNanos(Config.get().getDataStreamsLiteRefreshSeconds()) * 6 / 5);
    dataStreams.report();
    awaitIdle();
    checkpoint(PRODUCE);
    checkpoint(PRODUCE);

    assertEquals(2, queued.size());
  }

  @Test
  void errorsReportPathwaysAgainAtMostOncePerMinute() {
    startLiteDataStreams();
    checkpoint(PRODUCE);

    dataStreams.onEvent(EventListener.EventType.ERROR, "");
    checkpoint(PRODUCE);
    dataStreams.onEvent(EventListener.EventType.BAD_PAYLOAD, "");
    checkpoint(PRODUCE);
    assertEquals(2, queued.size());

    timeSource.advance(LITE_ERROR_RESET_INTERVAL_NANOS);
    dataStreams.onEvent(EventListener.EventType.ERROR, "");
    checkpoint(PRODUCE);
    assertEquals(3, queued.size());
  }

  @Test
  void nothingIsWrittenWhenNoPathwayIsNew() throws InterruptedException {
    startLiteDataStreams();
    checkpoint(PRODUCE);
    timeSource.advance(BUCKET_DURATION_NANOS);
    dataStreams.report();
    awaitPayloads(1);

    checkpoint(PRODUCE);
    timeSource.advance(BUCKET_DURATION_NANOS);
    dataStreams.report();
    awaitIdle();

    assertEquals(1, payloadWriter.buckets.size());
  }

  @Test
  void groupsCarryThePathwayButNoHistograms() throws InterruptedException {
    startLiteDataStreams();
    checkpoint(DataStreamsTags.create("internal", INBOUND));
    timeSource.advance(MILLISECONDS.toNanos(5));
    PathwayContext context = dataStreams.newPathwayContext();
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", INBOUND)), this::add);
    context.setCheckpoint(create(PRODUCE, 0, 1234), this::add);
    timeSource.advance(BUCKET_DURATION_NANOS);
    dataStreams.report();
    awaitPayloads(1);

    StatsPoint produced = queued.get(queued.size() - 1);
    assertEquals(0, produced.getPathwayLatencyNano());
    assertEquals(0, produced.getEdgeLatencyNano());
    assertEquals(0, produced.getPayloadSizeBytes());
    assertEquals(2, payloadWriter.groups().size());
    for (StatsGroup group : payloadWriter.groups()) {
      assertFalse(group.hasHistograms());
    }
    assertTrue(
        payloadWriter.groups().stream()
            .anyMatch(group ->
                group.getHash() == produced.getHash()
                    && group.getParentHash() == produced.getParentHash()));
  }

  @Test
  void nonPathwayItemsAreNotQueued() {
    dataStreams = liteDataStreams();

    dataStreams.trackBacklog(
        DataStreamsTags.createWithPartition("kafka_commit", "topic", "0", "cluster", "group"), 1);
    dataStreams.reportSchemaRegistryUsage("topic", "cluster", 1, true, false, "serialize");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", new java.util.HashMap<>());
    dataStreams.reportKafkaConsumerGroupMember("cluster", "group", "member", 1, "range");
    dataStreams.trackTransaction("transaction", "checkpoint");

    assertTrue(isInboxEmpty(dataStreams));
  }

  @ParameterizedTest(name = "with default timestamp {0}")
  @ValueSource(booleans = {false, true})
  void headersAreIdenticalToFullMode(boolean withDefaultTimestamp) throws IOException {
    timeSource.set(SECONDS.toNanos(1_700_000_000L));
    PathwaySeenSet seenSet = new PathwaySeenSet();
    List<StatsPoint> litePoints = new ArrayList<>();
    Consumer<StatsPoint> liteConsumer =
        point -> {
          litePoints.add(point);
          seenSet.insert(point.getAggregationHash());
        };

    for (int pass = 0; pass < 2; pass++) {
      DefaultPathwayContext full = new DefaultPathwayContext(timeSource, null);
      DefaultPathwayContext lite = new DefaultPathwayContext(timeSource, null, seenSet);

      DataStreamsContext first =
          withDefaultTimestamp
              ? create(PRODUCE, timeSource.getCurrentTimeMillis() - 1500, 0)
              : fromTags(PRODUCE);
      full.setCheckpoint(first, point -> {});
      lite.setCheckpoint(first, liteConsumer);
      assertSameHeader(full, lite);

      timeSource.advance(MILLISECONDS.toNanos(25));
      DataStreamsContext consume =
          fromTags(DataStreamsTags.create("kafka", INBOUND, "orders", "billing", null));
      full.setCheckpoint(consume, point -> {});
      lite.setCheckpoint(consume, liteConsumer);
      assertSameHeader(full, lite);

      timeSource.advance(MILLISECONDS.toNanos(40));
      DefaultPathwayContext fullDownstream =
          DefaultPathwayContext.decode(timeSource, null, full.encode());
      DefaultPathwayContext liteDownstream =
          DefaultPathwayContext.decode(timeSource, null, seenSet, lite.encode());
      timeSource.advance(MILLISECONDS.toNanos(10));
      DataStreamsContext produce = fromTags(DataStreamsTags.create("kafka", OUTBOUND, "invoices"));
      fullDownstream.setCheckpoint(produce, point -> {});
      liteDownstream.setCheckpoint(produce, liteConsumer);
      assertSameHeader(fullDownstream, liteDownstream);
    }

    assertEquals(3, litePoints.size());
  }

  private static void assertSameHeader(DefaultPathwayContext full, DefaultPathwayContext lite)
      throws IOException {
    assertEquals(full.getHash(), lite.getHash());
    assertEquals(full.encode(), lite.encode());
  }

  private void add(StatsPoint point) {
    if (dataStreams.add(point)) {
      queued.add(point);
    }
  }

  private void checkpoint(DataStreamsTags tags) {
    dataStreams.newPathwayContext().setCheckpoint(fromTags(tags), this::add);
  }

  private DefaultDataStreamsMonitoring liteDataStreams() {
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);
    return new DefaultDataStreamsMonitoring(
        mock(Sink.class),
        features,
        timeSource,
        () -> traceConfig,
        payloadWriter,
        BUCKET_DURATION_NANOS,
        true);
  }

  private void startLiteDataStreams() {
    dataStreams = liteDataStreams();
    dataStreams.start();
  }

  private void awaitIdle() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < deadline) {
      if (isInboxEmpty(dataStreams) && getThreadState(dataStreams) != Thread.State.RUNNABLE) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Data streams thread did not go idle");
  }

  private void awaitPayloads(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 1000;
    while (payloadWriter.buckets.size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(count, payloadWriter.buckets.size());
  }

  private static DDAgentFeaturesDiscovery stubFeatures(boolean supportsDataStreams) {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenReturn(supportsDataStreams);
    return features;
  }

  static class RecordingPayloadWriter implements DatastreamsPayloadWriter {
    final List<StatsBucket> buckets = new CopyOnWriteArrayList<>();

    @Override
    public void writePayload(Collection<StatsBucket> payload, String serviceNameOverride) {
      buckets.addAll(payload);
    }

    List<StatsGroup> groups() {
      List<StatsGroup> groups = new ArrayList<>();
      for (StatsBucket bucket : buckets) {
        groups.addAll(bucket.getGroups());
      }
      return groups;
    }
  }
}

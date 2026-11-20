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
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.context.Context;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
  void errorTooSoonAfterAResetIsDeferredToALaterReport() throws InterruptedException {
    startLiteDataStreams();
    checkpoint(PRODUCE);
    dataStreams.onEvent(EventListener.EventType.ERROR, "");
    checkpoint(PRODUCE);
    assertEquals(2, queued.size());

    timeSource.advance(SECONDS.toNanos(10));
    dataStreams.onEvent(EventListener.EventType.ERROR, "");
    checkpoint(PRODUCE);
    assertEquals(2, queued.size());

    timeSource.advance(LITE_ERROR_RESET_INTERVAL_NANOS);
    dataStreams.report();
    awaitIdle();
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

  @Test
  void originProducesOverTheBudgetGetTheUnsampledHeaderAndNoCheckpoint() throws Exception {
    dataStreams = liteDataStreams(1);
    dataStreams.start();

    withTracer(() -> {
      assertNotEquals(UNSAMPLED_HEADER, injectFromOrigin(PRODUCE), "first message on the edge");
      assertNotEquals(UNSAMPLED_HEADER, injectFromOrigin(PRODUCE), "within the budget");
      for (int i = 0; i < 100; i++) {
        assertEquals(UNSAMPLED_HEADER, injectFromOrigin(PRODUCE));
      }
    });

    timeSource.advance(BUCKET_DURATION_NANOS);
    dataStreams.report();
    awaitPayloads(1);
    assertEquals(1, payloadWriter.groups().size());
  }

  @Test
  void producesWithIncomingContextAreAlwaysPropagated() throws Exception {
    dataStreams = liteDataStreams(1);
    dataStreams.start();
    DataStreamsTags consume = DataStreamsTags.create("kafka", INBOUND, "orders", "billing", null);

    withTracer(() -> {
      for (int i = 0; i < 100; i++) {
        injectFromOrigin(PRODUCE);
      }
      for (int i = 0; i < 100; i++) {
        PathwayContext upstream = new DefaultPathwayContext(timeSource, null);
        upstream.setCheckpoint(fromTags(consume), point -> {});
        String header = inject(upstream, PRODUCE);
        assertNotNull(header);
        assertNotEquals(UNSAMPLED_HEADER, header);
      }
    });
  }

  @Test
  void aConsumeWithoutAHeaderDoesNothing() throws Exception {
    dataStreams = liteDataStreams(1000);
    dataStreams.start();
    PathwayContext spanPathway = dataStreams.newPathwayContext();
    assertSame(NoopPathwayContext.INSTANCE, spanPathway);

    dataStreams.setCheckpoint(
        spanWith(spanPathway),
        fromTags(DataStreamsTags.create("kafka", INBOUND, "orders", "billing", null)));

    assertFalse(spanPathway.isStarted());
    timeSource.advance(BUCKET_DURATION_NANOS);
    dataStreams.report();
    awaitIdle();
    assertTrue(payloadWriter.buckets.isEmpty());
  }

  @Test
  void sampledOriginHeadersAreIdenticalToUncappedLite() throws Exception {
    timeSource.set(SECONDS.toNanos(1_700_000_000L));
    DefaultDataStreamsMonitoring uncapped = liteDataStreams(0);
    uncapped.start();
    dataStreams = liteDataStreams(1000);
    dataStreams.start();
    try {
      withTracer(() -> {
        for (int i = 0; i < 3; i++) {
          String expected =
              inject(uncapped, uncapped.newPathwayContext(), PRODUCE).get(PROPAGATION_KEY);
          String actual = injectFromOrigin(PRODUCE);
          assertNotNull(actual);
          assertEquals(expected, actual);
          timeSource.advance(MILLISECONDS.toNanos(7));
        }
      });
    } finally {
      uncapped.close();
    }
  }

  @Test
  void pathwaysOnlyStartAtTrueOriginsWhenOriginsAreOverBudget() throws Exception {
    List<RecordedPoint> points = new CopyOnWriteArrayList<>();
    Service a = new Service("A", true, 1, points);
    Service b = new Service("B", true, 1, points);
    Service c = new Service("C", true, 1, points);
    Service d = new Service("D", true, 1, points);

    withTracer(() -> {
      for (int i = 0; i < 100; i++) {
        Map<String, String> toB = a.produce(a.originSpan(), "orders");
        Map<String, String> toC = b.produce(b.consume(toB, "orders"), "invoices");
        Map<String, String> toD = c.produce(c.consume(toC, "invoices"), "receipts");
        d.consume(toD, "receipts").finish();
      }
    });

    assertPathwaysOnlyStartAt(points, "A");
    assertTrue(points.stream().anyMatch(point -> point.service.equals("D")), points.toString());
  }

  @Test
  void aMessageWithNoHeaderStillStartsAPathway() throws Exception {
    List<RecordedPoint> points = new CopyOnWriteArrayList<>();
    Service b = new Service("B", true, 1, points);
    Service c = new Service("C", true, 1, points);

    withTracer(() -> {
      for (int i = 0; i < 100; i++) {
        Map<String, String> fromUninstrumented = new HashMap<>();
        Map<String, String> toC = b.produce(b.consume(fromUninstrumented, "orders"), "invoices");
        c.consume(toC, "invoices").finish();
      }
    });

    assertPathwaysOnlyStartAt(points, "B");
    assertTrue(points.stream().anyMatch(point -> point.service.equals("C")), points.toString());
  }

  @Test
  void aFullModeServiceRecordsNothingForAnUnsampledMessageAndForwardsIt() throws Exception {
    List<RecordedPoint> points = new CopyOnWriteArrayList<>();
    Service a = new Service("A", true, 1, points);
    Service b = new Service("B", false, 0, points);
    Service c = new Service("C", true, 1, points);

    withTracer(() -> {
      for (int i = 0; i < 100; i++) {
        Map<String, String> toB = a.produce(a.originSpan(), "orders");
        Map<String, String> toC = b.produce(b.consume(toB, "orders"), "invoices");
        if (UNSAMPLED_HEADER.equals(toB.get(PROPAGATION_KEY))) {
          assertEquals(UNSAMPLED_HEADER, toC.get(PROPAGATION_KEY));
        }
        c.consume(toC, "invoices").finish();
      }
    });

    assertPathwaysOnlyStartAt(points, "A");
    assertTrue(points.stream().anyMatch(point -> point.service.equals("B")), points.toString());
  }

  @Test
  void relaysKeepEachMessagesSamplingDecisionAndDoNotSpendTheirOwnOriginBudget() throws Exception {
    List<RecordedPoint> points = new CopyOnWriteArrayList<>();
    Service a = new Service("A", true, 1, points);
    Service b = new Service("B", true, 1, points);
    Service c = new Service("C", true, 1, points);
    Service bWithoutRelaying = new Service("B'", true, 1, new CopyOnWriteArrayList<>());
    List<Boolean> originDecisions = new ArrayList<>();
    List<Boolean> bOwnDecisions = new ArrayList<>();
    List<Boolean> expectedOwnDecisions = new ArrayList<>();

    withTracer(() -> {
      for (int second = 0; second < 5; second++) {
        for (int i = 0; i < 3; i++) {
          Map<String, String> toB = a.produce(a.originSpan(), "orders");
          Map<String, String> toC = b.produce(b.consume(toB, "orders"), "invoices");
          originDecisions.add(isUnsampled(toB));
          assertEquals(isUnsampled(toB), isUnsampled(toC), "relayed " + toB + " as " + toC);
          c.consume(toC, "invoices").finish();
        }
        // B is also a true origin for messages from an uninstrumented upstream
        for (int i = 0; i < 2; i++) {
          bOwnDecisions.add(isUnsampled(b.produce(b.consume(new HashMap<>(), "raw"), "alerts")));
          expectedOwnDecisions.add(
              isUnsampled(
                  bWithoutRelaying.produce(
                      bWithoutRelaying.consume(new HashMap<>(), "raw"), "alerts")));
        }
        timeSource.advance(SECONDS.toNanos(1));
      }
    });

    assertTrue(originDecisions.contains(true) && originDecisions.contains(false));
    assertTrue(expectedOwnDecisions.contains(true) && expectedOwnDecisions.contains(false));
    assertEquals(expectedOwnDecisions, bOwnDecisions);
    assertPathwaysOnlyStartAt(
        points,
        point ->
            point.service.equals("A")
                || (point.service.equals("B") && point.edge.endsWith("topic:alerts")));
    assertTrue(points.stream().anyMatch(point -> point.service.equals("C")), points.toString());
  }

  @Test
  void aBatchKeepsTheSampledPathwayOverUnsampledMessages() throws Exception {
    Service a = new Service("A", true, 1, new CopyOnWriteArrayList<>());
    Service b = new Service("B", true, 1, new CopyOnWriteArrayList<>());
    Map<String, String> unsampled = singletonMap(PROPAGATION_KEY, UNSAMPLED_HEADER);
    Map<String, String> sampled = new HashMap<>();
    withTracer(() -> sampled.putAll(a.produce(a.originSpan(), "orders")));
    assertFalse(isUnsampled(sampled), sampled.toString());
    long sampledHash =
        DefaultPathwayContext.decode(timeSource, null, sampled.get(PROPAGATION_KEY)).getHash();

    for (int i = 0; i < 64; i++) {
      assertEquals(sampledHash, b.consumeBatch(asList(unsampled, sampled)).getHash());
      assertEquals(sampledHash, b.consumeBatch(asList(sampled, unsampled)).getHash());
    }
    assertSame(UnsampledPathwayContext.INSTANCE, b.consumeBatch(asList(unsampled, unsampled)));
  }

  private static void assertPathwaysOnlyStartAt(List<RecordedPoint> points, String origin) {
    assertPathwaysOnlyStartAt(points, point -> point.service.equals(origin));
  }

  /** Every root checkpoint is at a true origin, and every other one continues a recorded one. */
  private static void assertPathwaysOnlyStartAt(
      List<RecordedPoint> points, Predicate<RecordedPoint> isTrueOrigin) {
    assertFalse(points.isEmpty());
    List<Long> hashes = new ArrayList<>();
    for (RecordedPoint point : points) {
      hashes.add(point.hash);
    }
    for (RecordedPoint point : points) {
      if (point.parentHash == 0) {
        assertTrue(isTrueOrigin.test(point), "pathway rooted at " + point + " in " + points);
      } else {
        assertTrue(hashes.contains(point.parentHash), "orphan " + point + " in " + points);
      }
    }
  }

  private static final class RecordedPoint {
    final String service;
    final String edge;
    final long hash;
    final long parentHash;

    RecordedPoint(String service, StatsPoint point) {
      this.service = service;
      this.edge = point.getTags().getDirection() + " " + point.getTags().getTopic();
      this.hash = point.getHash();
      this.parentHash = point.getParentHash();
    }

    @Override
    public String toString() {
      return service + "[" + edge + " hash=" + hash + " parent=" + parentHash + "]";
    }
  }

  /** One instrumented process: its own tracer, Data Streams monitoring and origin budget. */
  private final class Service {
    final String name;
    final DefaultDataStreamsMonitoring monitoring;
    final CoreTracer tracer;

    Service(String name, boolean lite, int originCapPerSecond, List<RecordedPoint> points) {
      this.name = name;
      TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
      when(traceConfig.isDataStreamsEnabled()).thenReturn(true);
      this.monitoring =
          new DefaultDataStreamsMonitoring(
              mock(Sink.class),
              features,
              timeSource,
              () -> traceConfig,
              payloadWriter,
              BUCKET_DURATION_NANOS,
              lite,
              lite,
              originCapPerSecond) {
            @Override
            public boolean add(StatsPoint point) {
              boolean queued = super.add(point);
              if (queued) {
                points.add(new RecordedPoint(Service.this.name, point));
              }
              return queued;
            }
          };
      this.tracer =
          tracerBuilder().writer(new ListWriter()).dataStreamsMonitoring(monitoring).build();
    }

    AgentSpan originSpan() {
      return tracer.buildSpan("test", "origin").start();
    }

    /** Starts the span that handles a received message, as messaging instrumentations do. */
    AgentSpan consume(Map<String, String> headers, String topic) {
      AgentSpan extracted =
          AgentSpan.fromContext(
              monitoring
                  .propagator()
                  .extract(
                      Context.root(), headers, (carrier, visitor) -> carrier.forEach(visitor)));
      AgentSpan span =
          tracer
              .buildSpan("test", "consume")
              .asChildOf(extracted != null ? extracted.spanContext() : null)
              .start();
      monitoring.setCheckpoint(
          span, fromTags(DataStreamsTags.create("kafka", INBOUND, topic, "group", null)));
      return span;
    }

    /**
     * Consumes a batch in one span, merging each message's pathway into it as {@code
     * setConsumeCheckpoint} does, and returns the span's resulting pathway.
     */
    PathwayContext consumeBatch(List<Map<String, String>> messages) {
      AgentSpan span = tracer.buildSpan("test", "consume-batch").start();
      for (Map<String, String> headers : messages) {
        monitoring.mergePathwayContextIntoSpan(span, carrier(headers));
      }
      span.finish();
      return span.spanContext().getPathwayContext();
    }

    /** Produces a message from a child of {@code parent}, then finishes {@code parent}. */
    Map<String, String> produce(AgentSpan parent, String topic) {
      AgentSpan span = tracer.buildSpan("test", "produce").asChildOf(parent).start();
      Map<String, String> headers = new HashMap<>();
      monitoring
          .propagator()
          .inject(
              span.with(fromTags(DataStreamsTags.create("kafka", OUTBOUND, topic))),
              headers,
              Map::put);
      span.finish();
      parent.finish();
      return headers;
    }
  }

  private static final String PROPAGATION_KEY = PathwayContext.PROPAGATION_KEY_BASE64;
  private static final String UNSAMPLED_HEADER = "AAAAAAAAAAA=";

  private static boolean isUnsampled(Map<String, String> headers) {
    return UNSAMPLED_HEADER.equals(headers.get(PROPAGATION_KEY));
  }

  /** Injects for a span with no incoming pathway context; returns the header or null. */
  private String injectFromOrigin(DataStreamsTags tags) {
    return inject(dataStreams.newPathwayContext(), tags);
  }

  private String inject(PathwayContext spanPathway, DataStreamsTags tags) {
    return inject(dataStreams, spanPathway, tags).get(PROPAGATION_KEY);
  }

  private static Map<String, String> inject(
      DefaultDataStreamsMonitoring monitoring, PathwayContext spanPathway, DataStreamsTags tags) {
    Map<String, String> headers = new HashMap<>();
    monitoring
        .propagator()
        .inject(Context.root().with(fromTags(tags)).with(spanWith(spanPathway)), headers, Map::put);
    return headers;
  }

  private static DataStreamsContextCarrier carrier(Map<String, String> headers) {
    return new DataStreamsContextCarrier() {
      @Override
      public Set<Map.Entry<String, Object>> entries() {
        return new HashMap<String, Object>(headers).entrySet();
      }

      @Override
      public void set(String key, String value) {
        headers.put(key, value);
      }
    };
  }

  private static AgentSpan spanWith(PathwayContext pathwayContext) {
    AgentSpanContext spanContext = mock(AgentSpanContext.class);
    when(spanContext.getPathwayContext()).thenReturn(pathwayContext);
    AgentSpan span = mock(AgentSpan.class);
    when(span.spanContext()).thenReturn(spanContext);
    when(span.storeInto(any())).thenCallRealMethod();
    return span;
  }

  private interface Body {
    void run() throws Exception;
  }

  /** The propagator reads whether DSM is on from the registered tracer. */
  private static void withTracer(Body body) throws Exception {
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class, RETURNS_SMART_NULLS);
    when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    AgentTracer.TracerAPI original = AgentTracer.get();
    AgentTracer.forceRegister(tracer);
    try {
      body.run();
    } finally {
      AgentTracer.forceRegister(original);
    }
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
    return liteDataStreams(0);
  }

  private DefaultDataStreamsMonitoring liteDataStreams(int originCapPerSecond) {
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);
    return new DefaultDataStreamsMonitoring(
        mock(Sink.class),
        features,
        timeSource,
        () -> traceConfig,
        payloadWriter,
        BUCKET_DURATION_NANOS,
        true,
        true,
        originCapPerSecond);
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

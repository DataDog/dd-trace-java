package datadog.trace.core.datastreams;

import static datadog.context.Context.root;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsContext.fromTags;
import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.BaseHash;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DefaultPathwayContextTest extends DDCoreSpecification {
  long baseHash = 12;

  static final long DEFAULT_BUCKET_DURATION_NANOS =
      Config.get().getDataStreamsBucketDurationNanoseconds();

  static class PointConsumer implements Consumer<StatsPoint> {
    List<StatsPoint> points = new ArrayList<>();

    @Override
    public void accept(StatsPoint point) {
      points.add(point);
    }
  }

  static class Base64MapContextVisitor
      implements AgentPropagation.ContextVisitor<Map<String, String>> {
    @Override
    public void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, String> entry : carrier.entrySet()) {
        classifier.accept(entry.getKey(), entry.getValue());
      }
    }
  }

  void verifyFirstPoint(StatsPoint point) {
    assertEquals(0, point.getParentHash());
    assertEquals(0, point.getPathwayLatencyNano());
    assertEquals(0, point.getEdgeLatencyNano());
    assertEquals(0, point.getPayloadSizeBytes());
  }

  @Test
  void firstSetCheckpointStartsTheContext() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(50);
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", null)), pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(1, pointConsumer.points.size());
    verifyFirstPoint(pointConsumer.points.get(0));
  }

  @Test
  void checkpointGenerated() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(50);
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
        pointConsumer);
    timeSource.advance(25);
    DataStreamsTags tags =
        DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null);
    context.setCheckpoint(fromTags(tags), pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(2, pointConsumer.points.size());
    verifyFirstPoint(pointConsumer.points.get(0));
    StatsPoint point1 = pointConsumer.points.get(1);
    assertEquals("group:group", point1.getTags().getGroup());
    assertEquals("topic:topic", point1.getTags().getTopic());
    assertEquals("type:kafka", point1.getTags().getType());
    assertEquals("direction:out", point1.getTags().getDirection());
    assertEquals(4, point1.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), point1.getParentHash());
    assertNotEquals(0, point1.getHash());
    assertEquals(25, point1.getPathwayLatencyNano());
    assertEquals(25, point1.getEdgeLatencyNano());
  }

  @Test
  void checkpointWithPayloadSize() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(25);
    context.setCheckpoint(
        create(DataStreamsTags.create("kafka", null, "topic", "group", null), 0, 72),
        pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(1, pointConsumer.points.size());
    StatsPoint point = pointConsumer.points.get(0);
    assertEquals("group:group", point.getTags().getGroup());
    assertEquals("topic:topic", point.getTags().getTopic());
    assertEquals("type:kafka", point.getTags().getType());
    assertEquals(3, point.getTags().nonNullSize());
    assertNotEquals(0, point.getHash());
    assertEquals(72, point.getPayloadSizeBytes());
  }

  @Test
  void multipleCheckpointsGenerated() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(50);
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND)),
        pointConsumer);
    timeSource.advance(25);
    DataStreamsTags tg =
        DataStreamsTags.create("kafka", DataStreamsTags.Direction.INBOUND, "topic", "group", null);
    context.setCheckpoint(fromTags(tg), pointConsumer);
    timeSource.advance(30);
    context.setCheckpoint(fromTags(tg), pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(3, pointConsumer.points.size());
    verifyFirstPoint(pointConsumer.points.get(0));

    StatsPoint point1 = pointConsumer.points.get(1);
    assertEquals(4, point1.getTags().nonNullSize());
    assertEquals("direction:in", point1.getTags().getDirection());
    assertEquals("group:group", point1.getTags().getGroup());
    assertEquals("topic:topic", point1.getTags().getTopic());
    assertEquals("type:kafka", point1.getTags().getType());
    assertEquals(pointConsumer.points.get(0).getHash(), point1.getParentHash());
    assertNotEquals(0, point1.getHash());
    assertEquals(25, point1.getPathwayLatencyNano());
    assertEquals(25, point1.getEdgeLatencyNano());

    StatsPoint point2 = pointConsumer.points.get(2);
    assertEquals(4, point2.getTags().nonNullSize());
    assertEquals("direction:in", point2.getTags().getDirection());
    assertEquals("group:group", point2.getTags().getGroup());
    assertEquals("topic:topic", point2.getTags().getTopic());
    assertEquals("type:kafka", point2.getTags().getType());
    // this point should have the first point as parent,
    // as the loop protection will reset the parent if two identical
    // points (same hash for tag values) are about to form a hierarchy
    assertEquals(pointConsumer.points.get(0).getHash(), point2.getParentHash());
    assertNotEquals(0, point2.getHash());
    assertEquals(55, point2.getPathwayLatencyNano());
    assertEquals(30, point2.getEdgeLatencyNano());
  }

  @Test
  void exceptionThrownWhenTryingToEncodeUnstartedContext() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

    assertThrows(IllegalStateException.class, context::encode);
  }

  @Test
  void setCheckpointWithDatasetTags() throws Exception {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(MILLISECONDS.toNanos(50));
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.createWithDataset(
                "s3", DataStreamsTags.Direction.INBOUND, null, "my_object.csv", "my_bucket")),
        pointConsumer);
    String encoded = context.encode();
    timeSource.advance(MILLISECONDS.toNanos(2));
    DefaultPathwayContext decodedContext = DefaultPathwayContext.decode(timeSource, null, encoded);
    timeSource.advance(MILLISECONDS.toNanos(25));
    DataStreamsTags tg =
        DataStreamsTags.createWithDataset(
            "s3", DataStreamsTags.Direction.OUTBOUND, null, "my_object.csv", "my_bucket");
    context.setCheckpoint(fromTags(tg), pointConsumer);

    assertTrue(decodedContext.isStarted());
    assertEquals(2, pointConsumer.points.size());

    // all points should have datasetHash, which is not equal to hash or 0
    for (int i = 0; i < pointConsumer.points.size(); i++) {
      assertNotEquals(
          pointConsumer.points.get(i).getHash(), pointConsumer.points.get(i).getAggregationHash());
      assertNotEquals(0, pointConsumer.points.get(i).getAggregationHash());
    }
  }

  @Test
  void encodingAndDecodingBase64AContext() throws Exception {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(MILLISECONDS.toNanos(50));
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
        pointConsumer);
    String encoded = context.encode();
    timeSource.advance(MILLISECONDS.toNanos(2));
    DefaultPathwayContext decodedContext = DefaultPathwayContext.decode(timeSource, null, encoded);
    timeSource.advance(MILLISECONDS.toNanos(25));
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("kafka", null, "topic", "group", null)), pointConsumer);

    assertTrue(decodedContext.isStarted());
    assertEquals(2, pointConsumer.points.size());

    StatsPoint point1 = pointConsumer.points.get(1);
    assertEquals(3, point1.getTags().nonNullSize());
    assertEquals("group:group", point1.getTags().getGroup());
    assertEquals("type:kafka", point1.getTags().getType());
    assertEquals("topic:topic", point1.getTags().getTopic());
    assertEquals(pointConsumer.points.get(0).getHash(), point1.getParentHash());
    assertNotEquals(0, point1.getHash());
    assertEquals(MILLISECONDS.toNanos(27), point1.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(27), point1.getEdgeLatencyNano());
  }

  @Test
  void setCheckpointWithTimestamp() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    long timeFromQueue = timeSource.getCurrentTimeMillis() - 200;
    context.setCheckpoint(
        create(DataStreamsTags.create("internal", null), timeFromQueue, 0), pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(1, pointConsumer.points.size());
    StatsPoint point = pointConsumer.points.get(0);
    assertEquals("type:internal", point.getTags().getType());
    assertEquals(1, point.getTags().nonNullSize());
    assertEquals(0, point.getParentHash());
    assertNotEquals(0, point.getHash());
    assertEquals(MILLISECONDS.toNanos(200), point.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(200), point.getEdgeLatencyNano());
  }

  @Test
  void encodingAndDecodingBase64WithContextsAndCheckpoints() throws Exception {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(MILLISECONDS.toNanos(50));
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
        pointConsumer);

    String encoded = context.encode();
    timeSource.advance(MILLISECONDS.toNanos(1));
    DefaultPathwayContext decodedContext = DefaultPathwayContext.decode(timeSource, null, encoded);
    timeSource.advance(MILLISECONDS.toNanos(25));
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.create(
                "kafka", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null)),
        pointConsumer);

    assertTrue(decodedContext.isStarted());
    assertEquals(2, pointConsumer.points.size());
    StatsPoint point1 = pointConsumer.points.get(1);
    assertEquals("group:group", point1.getTags().getGroup());
    assertEquals("topic:topic", point1.getTags().getTopic());
    assertEquals("type:kafka", point1.getTags().getType());
    assertEquals("direction:out", point1.getTags().getDirection());
    assertEquals(4, point1.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), point1.getParentHash());
    assertNotEquals(0, point1.getHash());
    assertEquals(MILLISECONDS.toNanos(26), point1.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(26), point1.getEdgeLatencyNano());

    String secondEncode = decodedContext.encode();
    timeSource.advance(MILLISECONDS.toNanos(2));
    DefaultPathwayContext secondDecode =
        DefaultPathwayContext.decode(timeSource, null, secondEncode);
    timeSource.advance(MILLISECONDS.toNanos(30));
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.create(
                "kafka", DataStreamsTags.Direction.INBOUND, "topicB", "group", null)),
        pointConsumer);

    assertTrue(secondDecode.isStarted());
    assertEquals(3, pointConsumer.points.size());
    StatsPoint point2 = pointConsumer.points.get(2);
    assertEquals("group:group", point2.getTags().getGroup());
    assertEquals("topic:topicB", point2.getTags().getTopic());
    assertEquals("type:kafka", point2.getTags().getType());
    assertEquals("direction:in", point2.getTags().getDirection());
    assertEquals(4, point2.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(1).getHash(), point2.getParentHash());
    assertNotEquals(0, point2.getHash());
    assertEquals(MILLISECONDS.toNanos(58), point2.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(32), point2.getEdgeLatencyNano());
  }

  @Test
  void encodingAndDecodingBase64WithInjectsAndExtracts() throws Exception {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(MILLISECONDS.toNanos(50));
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
        pointConsumer);

    String encoded = context.encode();
    Map<String, String> carrier = new HashMap<>();
    carrier.put(PROPAGATION_KEY_BASE64, encoded);
    carrier.put("someotherkey", "someothervalue");
    timeSource.advance(MILLISECONDS.toNanos(1));
    DefaultPathwayContext decodedContext =
        DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null);
    timeSource.advance(MILLISECONDS.toNanos(25));
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.create(
                "kafka", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null)),
        pointConsumer);

    assertTrue(decodedContext.isStarted());
    assertEquals(2, pointConsumer.points.size());
    StatsPoint point1 = pointConsumer.points.get(1);
    assertEquals(4, point1.getTags().nonNullSize());
    assertEquals("group:group", point1.getTags().getGroup());
    assertEquals("topic:topic", point1.getTags().getTopic());
    assertEquals("type:kafka", point1.getTags().getType());
    assertEquals("direction:out", point1.getTags().getDirection());
    assertEquals(pointConsumer.points.get(0).getHash(), point1.getParentHash());
    assertNotEquals(0, point1.getHash());
    assertEquals(MILLISECONDS.toNanos(26), point1.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(26), point1.getEdgeLatencyNano());

    String secondEncode = decodedContext.encode();
    carrier = new HashMap<>();
    carrier.put(PROPAGATION_KEY_BASE64, secondEncode);
    timeSource.advance(MILLISECONDS.toNanos(2));
    DefaultPathwayContext secondDecode =
        DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null);
    timeSource.advance(MILLISECONDS.toNanos(30));
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.create(
                "kafka", DataStreamsTags.Direction.INBOUND, "topicB", "group", null)),
        pointConsumer);

    assertTrue(secondDecode.isStarted());
    assertEquals(3, pointConsumer.points.size());
    StatsPoint point2 = pointConsumer.points.get(2);
    assertEquals(4, point2.getTags().nonNullSize());
    assertEquals("group:group", point2.getTags().getGroup());
    assertEquals("topic:topicB", point2.getTags().getTopic());
    assertEquals("type:kafka", point2.getTags().getType());
    assertEquals("direction:in", point2.getTags().getDirection());
    assertEquals(pointConsumer.points.get(1).getHash(), point2.getParentHash());
    assertNotEquals(0, point2.getHash());
    assertEquals(MILLISECONDS.toNanos(58), point2.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(32), point2.getEdgeLatencyNano());
  }

  @Test
  void encodingAndDecodingSQSFormattedWithInjectsAndExtracts() throws Exception {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(MILLISECONDS.toNanos(50));
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
        pointConsumer);

    String encoded = context.encode();
    Map<String, String> carrier = new HashMap<>();
    carrier.put(PROPAGATION_KEY_BASE64, encoded);
    carrier.put("someotherkey", "someothervalue");
    timeSource.advance(MILLISECONDS.toNanos(1));
    DefaultPathwayContext decodedContext =
        DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null);
    timeSource.advance(MILLISECONDS.toNanos(25));
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.create("sqs", DataStreamsTags.Direction.OUTBOUND, "topic", null, null)),
        pointConsumer);

    assertTrue(decodedContext.isStarted());
    assertEquals(2, pointConsumer.points.size());
    StatsPoint point1 = pointConsumer.points.get(1);
    assertEquals("direction:out", point1.getTags().getDirection());
    assertEquals("topic:topic", point1.getTags().getTopic());
    assertEquals("type:sqs", point1.getTags().getType());
    assertEquals(3, point1.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), point1.getParentHash());
    assertNotEquals(0, point1.getHash());
    assertEquals(MILLISECONDS.toNanos(26), point1.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(26), point1.getEdgeLatencyNano());

    String secondEncode = decodedContext.encode();
    carrier = new HashMap<>();
    carrier.put(PROPAGATION_KEY_BASE64, secondEncode);
    timeSource.advance(MILLISECONDS.toNanos(2));
    DefaultPathwayContext secondDecode =
        DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null);
    timeSource.advance(MILLISECONDS.toNanos(30));
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.create("sqs", DataStreamsTags.Direction.INBOUND, "topicB", null, null)),
        pointConsumer);

    assertTrue(secondDecode.isStarted());
    assertEquals(3, pointConsumer.points.size());
    StatsPoint point2 = pointConsumer.points.get(2);
    assertEquals("type:sqs", point2.getTags().getType());
    assertEquals("topic:topicB", point2.getTags().getTopic());
    assertEquals(3, point2.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(1).getHash(), point2.getParentHash());
    assertNotEquals(0, point2.getHash());
    assertEquals(MILLISECONDS.toNanos(58), point2.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(32), point2.getEdgeLatencyNano());
  }

  @Test
  void emptyTagsNotSet() throws Exception {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    PointConsumer pointConsumer = new PointConsumer();

    timeSource.advance(50);
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
        pointConsumer);
    timeSource.advance(25);
    context.setCheckpoint(
        fromTags(
            DataStreamsTags.create(
                "type", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null)),
        pointConsumer);
    timeSource.advance(25);
    context.setCheckpoint(fromTags(DataStreamsTags.create(null, null)), pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(3, pointConsumer.points.size());
    verifyFirstPoint(pointConsumer.points.get(0));

    StatsPoint point1 = pointConsumer.points.get(1);
    assertEquals("type:type", point1.getTags().getType());
    assertEquals("topic:topic", point1.getTags().getTopic());
    assertEquals("group:group", point1.getTags().getGroup());
    assertEquals("direction:out", point1.getTags().getDirection());
    assertEquals(4, point1.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), point1.getParentHash());
    assertNotEquals(0, point1.getHash());
    assertEquals(25, point1.getPathwayLatencyNano());
    assertEquals(25, point1.getEdgeLatencyNano());

    StatsPoint point2 = pointConsumer.points.get(2);
    assertEquals(0, point2.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(1).getHash(), point2.getParentHash());
    assertNotEquals(0, point2.getHash());
    assertEquals(50, point2.getPathwayLatencyNano());
    assertEquals(25, point2.getEdgeLatencyNano());
  }

  static Stream<Arguments> checkContextExtractorDecoratorBehaviorArguments() {
    return Stream.of(Arguments.of(true), Arguments.of(false));
  }

  @ParameterizedTest(
      name = "[{index}] Check context extractor decorator behavior dynamicConfigEnabled={0}")
  @MethodSource("checkContextExtractorDecoratorBehaviorArguments")
  void checkContextExtractorDecoratorBehavior(boolean dynamicConfigEnabled) throws Exception {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

    TraceConfig globalTraceConfig = mock(TraceConfig.class);
    when(globalTraceConfig.isDataStreamsEnabled()).thenReturn(dynamicConfigEnabled);

    AgentTracer.TracerAPI tracerApi = mock(AgentTracer.TracerAPI.class);
    when(tracerApi.captureTraceConfig()).thenReturn(globalTraceConfig);
    AgentTracer.TracerAPI originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracerApi);

    try {
      DefaultDataStreamsMonitoring dataStreams =
          new DefaultDataStreamsMonitoring(
              sink,
              features,
              timeSource,
              () -> globalTraceConfig,
              payloadWriter,
              DEFAULT_BUCKET_DURATION_NANOS);

      BaseHash.updateBaseHash(baseHash);
      DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
      PointConsumer pointConsumer = new PointConsumer();
      timeSource.advance(MILLISECONDS.toNanos(50));
      context.setCheckpoint(
          fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
          pointConsumer);
      String encoded = context.encode();
      Map<String, String> carrier = new HashMap<>();
      carrier.put(PROPAGATION_KEY_BASE64, encoded);
      carrier.put("someotherkey", "someothervalue");
      Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
      datadog.context.propagation.Propagator propagator = dataStreams.propagator();

      datadog.context.Context extractedContext =
          propagator.extract(root(), carrier, contextVisitor);
      AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

      assertEquals("L+lDG/Pa9hRkZA==", encoded);
      if (dynamicConfigEnabled) {
        assertNotNull(extractedSpan);
        datadog.trace.bootstrap.instrumentation.api.AgentSpanContext extracted =
            extractedSpan.context();
        assertNotNull(extracted);
        assertNotNull(extracted.getPathwayContext());
        assertTrue(extracted.getPathwayContext().isStarted());
      } else {
        // either null or exists but DSM context not started
      }
    } finally {
      AgentTracer.forceRegister(originalTracer);
    }
  }

  @ParameterizedTest(
      name =
          "[{index}] Check context extractor decorator behavior when trace data is null, globalDsmEnabled={0}")
  @MethodSource("checkContextExtractorDecoratorBehaviorArguments")
  void checkContextExtractorDecoratorBehaviorWhenTraceDataIsNull(boolean globalDsmEnabled)
      throws Exception {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

    TraceConfig globalTraceConfig = mock(TraceConfig.class);
    when(globalTraceConfig.isDataStreamsEnabled()).thenReturn(globalDsmEnabled);

    AgentTracer.TracerAPI tracerApi = mock(AgentTracer.TracerAPI.class);
    when(tracerApi.captureTraceConfig()).thenReturn(globalTraceConfig);
    AgentTracer.TracerAPI originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracerApi);

    try {
      DefaultDataStreamsMonitoring dataStreams =
          new DefaultDataStreamsMonitoring(
              sink,
              features,
              timeSource,
              () -> globalTraceConfig,
              payloadWriter,
              DEFAULT_BUCKET_DURATION_NANOS);

      BaseHash.updateBaseHash(baseHash);
      DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
      PointConsumer pointConsumer = new PointConsumer();
      timeSource.advance(MILLISECONDS.toNanos(50));
      context.setCheckpoint(
          fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
          pointConsumer);
      String encoded = context.encode();

      Map<String, String> carrier = new HashMap<>();
      carrier.put(PROPAGATION_KEY_BASE64, encoded);
      carrier.put("someotherkey", "someothervalue");
      Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
      datadog.context.propagation.Propagator propagator = dataStreams.propagator();

      datadog.context.Context extractedContext =
          propagator.extract(root(), carrier, contextVisitor);
      AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

      assertEquals("L+lDG/Pa9hRkZA==", encoded);
      if (globalDsmEnabled) {
        assertNotNull(extractedSpan);
        datadog.trace.bootstrap.instrumentation.api.AgentSpanContext extracted =
            extractedSpan.context();
        assertNotNull(extracted);
        assertNotNull(extracted.getPathwayContext());
        assertTrue(extracted.getPathwayContext().isStarted());
      } else {
        assertNull(extractedSpan);
      }
    } finally {
      AgentTracer.forceRegister(originalTracer);
    }
  }

  @ParameterizedTest(
      name =
          "[{index}] Check context extractor decorator behavior when local trace config is null, globalDsmEnabled={0}")
  @MethodSource("checkContextExtractorDecoratorBehaviorArguments")
  void checkContextExtractorDecoratorBehaviorWhenLocalTraceConfigIsNull(boolean globalDsmEnabled)
      throws Exception {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

    TraceConfig globalTraceConfig = mock(TraceConfig.class);
    when(globalTraceConfig.isDataStreamsEnabled()).thenReturn(globalDsmEnabled);

    AgentTracer.TracerAPI tracerApi = mock(AgentTracer.TracerAPI.class);
    when(tracerApi.captureTraceConfig()).thenReturn(globalTraceConfig);
    AgentTracer.TracerAPI originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracerApi);

    try {
      DefaultDataStreamsMonitoring dataStreams =
          new DefaultDataStreamsMonitoring(
              sink,
              features,
              timeSource,
              () -> globalTraceConfig,
              payloadWriter,
              DEFAULT_BUCKET_DURATION_NANOS);

      BaseHash.updateBaseHash(baseHash);
      DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
      PointConsumer pointConsumer = new PointConsumer();
      timeSource.advance(MILLISECONDS.toNanos(50));
      context.setCheckpoint(
          fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
          pointConsumer);
      String encoded = context.encode();
      Map<String, String> carrier = new HashMap<>();
      carrier.put(PROPAGATION_KEY_BASE64, encoded);
      carrier.put("someotherkey", "someothervalue");
      Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
      ExtractedContext spanContext =
          new ExtractedContext(
              DDTraceId.ONE,
              1,
              0,
              null,
              0,
              null,
              (TagMap) null,
              null,
              null,
              globalTraceConfig,
              DATADOG);
      datadog.context.Context baseContext =
          AgentSpan.fromSpanContext(spanContext).storeInto(root());
      datadog.context.propagation.Propagator propagator = dataStreams.propagator();

      datadog.context.Context extractedContext =
          propagator.extract(baseContext, carrier, contextVisitor);
      AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

      assertNotNull(extractedSpan);

      datadog.trace.bootstrap.instrumentation.api.AgentSpanContext extracted =
          extractedSpan.context();
      assertNotNull(extracted);
      assertEquals("L+lDG/Pa9hRkZA==", encoded);
      if (globalDsmEnabled) {
        assertNotNull(extracted.getPathwayContext());
        assertTrue(extracted.getPathwayContext().isStarted());
      } else {
        assertNull(extracted.getPathwayContext());
      }
    } finally {
      AgentTracer.forceRegister(originalTracer);
    }
  }

  @Test
  void checkContextExtractorDecoratorBehaviorWhenTraceDataAndDsmDataAreNull() {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

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

    Map<String, String> carrier = new HashMap<>();
    carrier.put("someotherkey", "someothervalue");
    Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
    datadog.context.propagation.Propagator propagator = dataStreams.propagator();

    datadog.context.Context extractedContext = propagator.extract(root(), carrier, contextVisitor);
    AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

    assertNull(extractedSpan);
  }
}

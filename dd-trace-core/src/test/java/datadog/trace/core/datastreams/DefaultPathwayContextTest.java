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
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.context.Context;
import datadog.context.propagation.Propagator;
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
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.propagation.ExtractedContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

public class DefaultPathwayContextTest extends DDCoreJavaSpecification {
  private static final long DEFAULT_BUCKET_DURATION_NANOS =
      Config.get().getDataStreamsBucketDurationNanoseconds();
  private static final long BASE_HASH = 12L;

  private CapturingPointConsumer pointConsumer;

  @BeforeEach
  void setupConsumer() {
    pointConsumer = new CapturingPointConsumer();
  }

  private static void verifyFirstPoint(StatsPoint point) {
    assertEquals(0L, point.getParentHash());
    assertEquals(0L, point.getPathwayLatencyNano());
    assertEquals(0L, point.getEdgeLatencyNano());
    assertEquals(0L, point.getPayloadSizeBytes());
  }

  @Test
  void firstSetCheckpointStartsTheContext() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

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
    StatsPoint second = pointConsumer.points.get(1);
    assertEquals("group:group", second.getTags().getGroup());
    assertEquals("topic:topic", second.getTags().getTopic());
    assertEquals("type:kafka", second.getTags().getType());
    assertEquals("direction:out", second.getTags().getDirection());
    assertEquals(4, second.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), second.getParentHash());
    assertNotEquals(0L, second.getHash());
    assertEquals(25L, second.getPathwayLatencyNano());
    assertEquals(25L, second.getEdgeLatencyNano());
  }

  @Test
  void checkpointWithPayloadSize() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

    timeSource.advance(25);
    context.setCheckpoint(
        create(DataStreamsTags.create("kafka", null, "topic", "group", null), 0, 72),
        pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(1, pointConsumer.points.size());
    StatsPoint first = pointConsumer.points.get(0);
    assertEquals("group:group", first.getTags().getGroup());
    assertEquals("topic:topic", first.getTags().getTopic());
    assertEquals("type:kafka", first.getTags().getType());
    assertEquals(3, first.getTags().nonNullSize());
    assertNotEquals(0L, first.getHash());
    assertEquals(72L, first.getPayloadSizeBytes());
  }

  @Test
  void multipleCheckpointsGenerated() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

    timeSource.advance(50);
    context.setCheckpoint(
        fromTags(DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND)),
        pointConsumer);
    timeSource.advance(25);
    DataStreamsTags tags =
        DataStreamsTags.create("kafka", DataStreamsTags.Direction.INBOUND, "topic", "group", null);
    context.setCheckpoint(fromTags(tags), pointConsumer);
    timeSource.advance(30);
    context.setCheckpoint(fromTags(tags), pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(3, pointConsumer.points.size());
    verifyFirstPoint(pointConsumer.points.get(0));
    StatsPoint second = pointConsumer.points.get(1);
    assertEquals(4, second.getTags().nonNullSize());
    assertEquals("direction:in", second.getTags().getDirection());
    assertEquals("group:group", second.getTags().getGroup());
    assertEquals("topic:topic", second.getTags().getTopic());
    assertEquals("type:kafka", second.getTags().getType());
    assertEquals(pointConsumer.points.get(0).getHash(), second.getParentHash());
    assertNotEquals(0L, second.getHash());
    assertEquals(25L, second.getPathwayLatencyNano());
    assertEquals(25L, second.getEdgeLatencyNano());
    StatsPoint third = pointConsumer.points.get(2);
    assertEquals(4, third.getTags().nonNullSize());
    assertEquals("direction:in", third.getTags().getDirection());
    assertEquals("group:group", third.getTags().getGroup());
    assertEquals("topic:topic", third.getTags().getTopic());
    assertEquals("type:kafka", third.getTags().getType());
    // this point should have the first point as parent,
    // as the loop protection will reset the parent if two identical
    // points (same hash for tag values) are about to form a hierarchy
    assertEquals(pointConsumer.points.get(0).getHash(), third.getParentHash());
    assertNotEquals(0L, third.getHash());
    assertEquals(55L, third.getPathwayLatencyNano());
    assertEquals(30L, third.getEdgeLatencyNano());
  }

  @Test
  void exceptionThrownWhenTryingToEncodeUnstartedContext() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

    assertThrows(IllegalStateException.class, context::encode);
  }

  @Test
  void setCheckpointWithDatasetTags() throws IOException {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

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
    DataStreamsTags tags =
        DataStreamsTags.createWithDataset(
            "s3", DataStreamsTags.Direction.OUTBOUND, null, "my_object.csv", "my_bucket");
    context.setCheckpoint(fromTags(tags), pointConsumer);

    assertTrue(decodedContext.isStarted());
    assertEquals(2, pointConsumer.points.size());

    // all points should have datasetHash, which is not equal to hash or 0
    for (StatsPoint point : pointConsumer.points) {
      assertNotEquals(point.getHash(), point.getAggregationHash());
      assertNotEquals(0L, point.getAggregationHash());
    }
  }

  @Test
  void encodingAndDecodingBase64AContext() throws IOException {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

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

    StatsPoint second = pointConsumer.points.get(1);
    assertEquals(3, second.getTags().nonNullSize());
    assertEquals("group:group", second.getTags().getGroup());
    assertEquals("type:kafka", second.getTags().getType());
    assertEquals("topic:topic", second.getTags().getTopic());
    assertEquals(pointConsumer.points.get(0).getHash(), second.getParentHash());
    assertNotEquals(0L, second.getHash());
    assertEquals(MILLISECONDS.toNanos(27), second.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(27), second.getEdgeLatencyNano());
  }

  @Test
  void setCheckpointWithTimestamp() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    long timeFromQueue = timeSource.getCurrentTimeMillis() - 200;

    context.setCheckpoint(
        create(DataStreamsTags.create("internal", null), timeFromQueue, 0), pointConsumer);

    assertTrue(context.isStarted());
    assertEquals(1, pointConsumer.points.size());
    StatsPoint first = pointConsumer.points.get(0);
    assertEquals("type:internal", first.getTags().getType());
    assertEquals(1, first.getTags().nonNullSize());
    assertEquals(0L, first.getParentHash());
    assertNotEquals(0L, first.getHash());
    assertEquals(MILLISECONDS.toNanos(200), first.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(200), first.getEdgeLatencyNano());
  }

  @Test
  void encodingAndDecodingBase64WithContextsAndCheckpoints() throws IOException {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

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
    StatsPoint second = pointConsumer.points.get(1);
    assertEquals("group:group", second.getTags().getGroup());
    assertEquals("topic:topic", second.getTags().getTopic());
    assertEquals("type:kafka", second.getTags().getType());
    assertEquals("direction:out", second.getTags().getDirection());
    assertEquals(4, second.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), second.getParentHash());
    assertNotEquals(0L, second.getHash());
    assertEquals(MILLISECONDS.toNanos(26), second.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(26), second.getEdgeLatencyNano());

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
    StatsPoint third = pointConsumer.points.get(2);
    assertEquals("group:group", third.getTags().getGroup());
    assertEquals("topic:topicB", third.getTags().getTopic());
    assertEquals("type:kafka", third.getTags().getType());
    assertEquals("direction:in", third.getTags().getDirection());
    assertEquals(4, third.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(1).getHash(), third.getParentHash());
    assertNotEquals(0L, third.getHash());
    assertEquals(MILLISECONDS.toNanos(58), third.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(32), third.getEdgeLatencyNano());
  }

  @Test
  void encodingAndDecodingBase64WithInjectsAndExtracts() throws IOException {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();

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
    StatsPoint second = pointConsumer.points.get(1);
    assertEquals(4, second.getTags().nonNullSize());
    assertEquals("group:group", second.getTags().getGroup());
    assertEquals("topic:topic", second.getTags().getTopic());
    assertEquals("type:kafka", second.getTags().getType());
    assertEquals("direction:out", second.getTags().getDirection());
    assertEquals(pointConsumer.points.get(0).getHash(), second.getParentHash());
    assertNotEquals(0L, second.getHash());
    assertEquals(MILLISECONDS.toNanos(26), second.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(26), second.getEdgeLatencyNano());

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
    StatsPoint third = pointConsumer.points.get(2);
    assertEquals(4, third.getTags().nonNullSize());
    assertEquals("group:group", third.getTags().getGroup());
    assertEquals("topic:topicB", third.getTags().getTopic());
    assertEquals("type:kafka", third.getTags().getType());
    assertEquals("direction:in", third.getTags().getDirection());
    assertEquals(pointConsumer.points.get(1).getHash(), third.getParentHash());
    assertNotEquals(0L, third.getHash());
    assertEquals(MILLISECONDS.toNanos(58), third.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(32), third.getEdgeLatencyNano());
  }

  @Test
  void encodingAndDecodingSqsFormattedWithInjectsAndExtracts() throws IOException {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
    Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();

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
    StatsPoint second = pointConsumer.points.get(1);
    assertEquals("direction:out", second.getTags().getDirection());
    assertEquals("topic:topic", second.getTags().getTopic());
    assertEquals("type:sqs", second.getTags().getType());
    assertEquals(3, second.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), second.getParentHash());
    assertNotEquals(0L, second.getHash());
    assertEquals(MILLISECONDS.toNanos(26), second.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(26), second.getEdgeLatencyNano());

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
    StatsPoint third = pointConsumer.points.get(2);
    assertEquals("type:sqs", third.getTags().getType());
    assertEquals("topic:topicB", third.getTags().getTopic());
    assertEquals(3, third.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(1).getHash(), third.getParentHash());
    assertNotEquals(0L, third.getHash());
    assertEquals(MILLISECONDS.toNanos(58), third.getPathwayLatencyNano());
    assertEquals(MILLISECONDS.toNanos(32), third.getEdgeLatencyNano());
  }

  @Test
  void emptyTagsNotSet() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);

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
    StatsPoint second = pointConsumer.points.get(1);
    assertEquals("type:type", second.getTags().getType());
    assertEquals("topic:topic", second.getTags().getTopic());
    assertEquals("group:group", second.getTags().getGroup());
    assertEquals("direction:out", second.getTags().getDirection());
    assertEquals(4, second.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(0).getHash(), second.getParentHash());
    assertNotEquals(0L, second.getHash());
    assertEquals(25L, second.getPathwayLatencyNano());
    assertEquals(25L, second.getEdgeLatencyNano());
    StatsPoint third = pointConsumer.points.get(2);
    assertEquals(0, third.getTags().nonNullSize());
    assertEquals(pointConsumer.points.get(1).getHash(), third.getParentHash());
    assertNotEquals(0L, third.getHash());
    assertEquals(50L, third.getPathwayLatencyNano());
    assertEquals(25L, third.getEdgeLatencyNano());
  }

  @TableTest({
    "scenario | dynamicConfigEnabled",
    "enabled  | true                ",
    "disabled | false               "
  })
  void checkContextExtractorDecoratorBehavior(boolean dynamicConfigEnabled) throws IOException {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

    TraceConfig globalTraceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(globalTraceConfig.isDataStreamsEnabled()).thenReturn(dynamicConfigEnabled);

    AgentTracer.TracerAPI tracerApi = mock(AgentTracer.TracerAPI.class, RETURNS_SMART_NULLS);
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

      BaseHash.updateBaseHash(BASE_HASH);
      DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
      timeSource.advance(MILLISECONDS.toNanos(50));
      context.setCheckpoint(
          fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
          pointConsumer);
      String encoded = context.encode();
      Map<String, String> carrier = new HashMap<>();
      carrier.put(PROPAGATION_KEY_BASE64, encoded);
      carrier.put("someotherkey", "someothervalue");
      Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
      Propagator propagator = dataStreams.propagator();

      Context extractedContext = propagator.extract(root(), carrier, contextVisitor);
      AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

      assertEquals("L+lDG/Pa9hRkZA==", encoded);
      if (dynamicConfigEnabled) {
        assertNotNull(extractedSpan);
        assertNotNull(extractedSpan.spanContext());
        assertNotNull(extractedSpan.spanContext().getPathwayContext());
        assertTrue(extractedSpan.spanContext().getPathwayContext().isStarted());
      }
    } finally {
      // cleanup
      AgentTracer.forceRegister(originalTracer);
    }
  }

  @TableTest({
    "scenario | globalDsmEnabled",
    "enabled  | true            ",
    "disabled | false           "
  })
  void checkContextExtractorDecoratorBehaviorWhenTraceDataIsNull(boolean globalDsmEnabled)
      throws IOException {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

    TraceConfig globalTraceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(globalTraceConfig.isDataStreamsEnabled()).thenReturn(globalDsmEnabled);

    AgentTracer.TracerAPI tracerApi = mock(AgentTracer.TracerAPI.class, RETURNS_SMART_NULLS);
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

      BaseHash.updateBaseHash(BASE_HASH);
      DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
      timeSource.advance(MILLISECONDS.toNanos(50));
      context.setCheckpoint(
          fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)),
          pointConsumer);
      String encoded = context.encode();

      Map<String, String> carrier = new HashMap<>();
      carrier.put(PROPAGATION_KEY_BASE64, encoded);
      carrier.put("someotherkey", "someothervalue");
      Base64MapContextVisitor contextVisitor = new Base64MapContextVisitor();
      Propagator propagator = dataStreams.propagator();

      Context extractedContext = propagator.extract(root(), carrier, contextVisitor);
      AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

      assertEquals("L+lDG/Pa9hRkZA==", encoded);
      if (globalDsmEnabled) {
        assertNotNull(extractedSpan);
        assertNotNull(extractedSpan.spanContext());
        assertNotNull(extractedSpan.spanContext().getPathwayContext());
        assertTrue(extractedSpan.spanContext().getPathwayContext().isStarted());
      } else {
        assertNull(extractedSpan);
      }
    } finally {
      // cleanup
      AgentTracer.forceRegister(originalTracer);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void checkContextExtractorDecoratorBehaviorWhenLocalTraceConfigIsNull(boolean globalDsmEnabled)
      throws IOException {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

    TraceConfig globalTraceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(globalTraceConfig.isDataStreamsEnabled()).thenReturn(globalDsmEnabled);

    AgentTracer.TracerAPI tracerApi = mock(AgentTracer.TracerAPI.class, RETURNS_SMART_NULLS);
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

      BaseHash.updateBaseHash(BASE_HASH);
      DefaultPathwayContext context = new DefaultPathwayContext(timeSource, null);
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
      Context baseContext = AgentSpan.fromSpanContext(spanContext).storeInto(root());
      Propagator propagator = dataStreams.propagator();

      Context extractedContext = propagator.extract(baseContext, carrier, contextVisitor);
      AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

      assertNotNull(extractedSpan);

      Object extracted = extractedSpan.spanContext();

      assertNotNull(extracted);
      assertEquals("L+lDG/Pa9hRkZA==", encoded);
      if (globalDsmEnabled) {
        assertNotNull(extractedSpan.spanContext().getPathwayContext());
        assertTrue(extractedSpan.spanContext().getPathwayContext().isStarted());
      } else {
        assertNull(extractedSpan.spanContext().getPathwayContext());
      }
    } finally {
      // cleanup
      AgentTracer.forceRegister(originalTracer);
    }
  }

  @Test
  void checkContextExtractorDecoratorBehaviorWhenTraceDataAndDsmDataAreNull() {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class, RETURNS_SMART_NULLS);
    when(features.supportsDataStreams()).thenReturn(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    DatastreamsPayloadWriter payloadWriter = mock(DatastreamsPayloadWriter.class);

    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
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
    Propagator propagator = dataStreams.propagator();

    Context extractedContext = propagator.extract(root(), carrier, contextVisitor);
    AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);

    assertNull(extractedSpan);
  }

  static class CapturingPointConsumer implements Consumer<StatsPoint> {
    final List<StatsPoint> points = new ArrayList<>();

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
}

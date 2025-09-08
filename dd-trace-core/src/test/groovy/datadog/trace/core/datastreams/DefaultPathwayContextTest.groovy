package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.BaseHash
import datadog.trace.api.Config
import datadog.trace.api.DDTraceId
import datadog.trace.api.TagMap
import datadog.trace.api.TraceConfig
import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.datastreams.StatsPoint
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.common.metrics.Sink
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.test.DDCoreSpecification
import java.util.function.Consumer
import static datadog.context.Context.root
import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.datastreams.DataStreamsContext.create
import static datadog.trace.api.datastreams.DataStreamsContext.fromTags
import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64
import static java.util.concurrent.TimeUnit.MILLISECONDS

class DefaultPathwayContextTest extends DDCoreSpecification {
  long baseHash = 12

  static final DEFAULT_BUCKET_DURATION_NANOS = Config.get().getDataStreamsBucketDurationNanoseconds()
  def pointConsumer = new Consumer<StatsPoint>() {
    List<StatsPoint> points = []

    @Override
    void accept(StatsPoint point) {
      points.add(point)
    }
  }

  void verifyFirstPoint(StatsPoint point) {
    assert point.parentHash == 0
    assert point.pathwayLatencyNano == 0
    assert point.edgeLatencyNano == 0
    assert point.payloadSizeBytes == 0
  }

  def "First Set checkpoint starts the context."() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(50)
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", null)), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    verifyFirstPoint(pointConsumer.points[0])
  }

  def "Checkpoint generated"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(50)
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)
    timeSource.advance(25)
    def tags = DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null)
    context.setCheckpoint(fromTags(tags), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 2
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      tags.group == "group:group"
      tags.topic == "topic:topic"
      tags.type == "type:kafka"
      tags.getDirection() == "direction:out"
      tags.nonNullSize() == 4
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
  }

  def "Checkpoint with payload size"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(25)
    context.setCheckpoint(
      create(DataStreamsTags.create("kafka", null, "topic", "group", null), 0, 72),
      pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    with(pointConsumer.points[0]) {
      tags.getGroup() == "group:group"
      tags.getTopic() == "topic:topic"
      tags.getType() == "type:kafka"
      tags.nonNullSize() == 3
      hash != 0
      payloadSizeBytes == 72
    }
  }

  def "Multiple checkpoints generated"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(50)
    context.setCheckpoint(fromTags(DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND)), pointConsumer)
    timeSource.advance(25)
    def tg = DataStreamsTags.create("kafka", DataStreamsTags.Direction.INBOUND, "topic", "group", null)
    context.setCheckpoint(fromTags(tg), pointConsumer)
    timeSource.advance(30)
    context.setCheckpoint(fromTags(tg), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 3
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      tags.nonNullSize() == 4
      tags.direction == "direction:in"
      tags.group == "group:group"
      tags.topic == "topic:topic"
      tags.type == "type:kafka"
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
    with(pointConsumer.points[2]) {
      tags.nonNullSize() == 4
      tags.direction == "direction:in"
      tags.group == "group:group"
      tags.topic == "topic:topic"
      tags.type == "type:kafka"
      // this point should have the first point as parent,
      // as the loop protection will reset the parent if two identical
      // points (same hash for tag values) are about to form a hierarchy
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 55
      edgeLatencyNano == 30
    }
  }

  def "Exception thrown when trying to encode unstarted context"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    context.encode()

    then:
    thrown(IllegalStateException)
  }

  def "Set checkpoint with dataset tags"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.createWithDataset("s3", DataStreamsTags.Direction.INBOUND, null, "my_object.csv", "my_bucket")), pointConsumer)
    def encoded = context.encode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def decodedContext = DefaultPathwayContext.decode(timeSource, null, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    def tg = DataStreamsTags.createWithDataset("s3", DataStreamsTags.Direction.OUTBOUND, null, "my_object.csv", "my_bucket")
    context.setCheckpoint(fromTags(tg), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2

    // all points should have datasetHash, which is not equal to hash or 0
    for (def i = 0; i < pointConsumer.points.size(); i++){
      pointConsumer.points[i].aggregationHash != pointConsumer.points[i].hash
      pointConsumer.points[i].aggregationHash != 0
    }
  }

  def "Encoding and decoding (base64) a context"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)
    def encoded = context.encode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def decodedContext = DefaultPathwayContext.decode(timeSource, null, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(fromTags(DataStreamsTags.create("kafka", null, "topic", "group", null)), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2

    with(pointConsumer.points[1]) {
      tags.nonNullSize() == 3
      tags.getGroup() == "group:group"
      tags.getType() == "type:kafka"
      tags.getTopic() == "topic:topic"
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(27)
      edgeLatencyNano == MILLISECONDS.toNanos(27)
    }
  }

  def "Set checkpoint with timestamp"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)
    def timeFromQueue = timeSource.getCurrentTimeMillis() - 200
    when:
    context.setCheckpoint(create(DataStreamsTags.create("internal", null), timeFromQueue, 0), pointConsumer)
    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    with(pointConsumer.points[0]) {
      tags.getType() == "type:internal"
      tags.nonNullSize() == 1
      parentHash == 0
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(200)
      edgeLatencyNano == MILLISECONDS.toNanos(200)
    }
  }

  def "Encoding and decoding (base64) with contexts and checkpoints"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)

    def encoded = context.encode()
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.decode(timeSource, null, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(fromTags(DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null)), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      tags.group == "group:group"
      tags.topic == "topic:topic"
      tags.type == "type:kafka"
      tags.direction == "direction:out"
      tags.nonNullSize() == 4
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.encode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.decode(timeSource, null, secondEncode)
    timeSource.advance(MILLISECONDS.toNanos(30))
    context.setCheckpoint(fromTags(DataStreamsTags.create("kafka", DataStreamsTags.Direction.INBOUND, "topicB", "group", null)), pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      tags.group == "group:group"
      tags.topic == "topic:topicB"
      tags.type == "type:kafka"
      tags.direction == "direction:in"
      tags.nonNullSize() == 4
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Encoding and decoding (base64) with injects and extracts"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)
    def contextVisitor = new Base64MapContextVisitor()

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)

    def encoded = context.encode()
    Map<String, String> carrier = [(PROPAGATION_KEY_BASE64): encoded, "someotherkey": "someothervalue"]
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(fromTags(DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null)), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      tags.nonNullSize() == 4
      tags.group == "group:group"
      tags.topic == "topic:topic"
      tags.type == "type:kafka"
      tags.direction == "direction:out"
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.encode()
    carrier = [(PROPAGATION_KEY_BASE64): secondEncode]
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null)
    timeSource.advance(MILLISECONDS.toNanos(30))
    context.setCheckpoint(fromTags(DataStreamsTags.create("kafka", DataStreamsTags.Direction.INBOUND, "topicB", "group", null)), pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      tags.nonNullSize() == 4
      tags.group == "group:group"
      tags.topic == "topic:topicB"
      tags.type == "type:kafka"
      tags.direction == "direction:in"
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Encoding and decoding (SQS-formatted) with injects and extracts"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)
    def contextVisitor = new Base64MapContextVisitor()

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)

    def encoded = context.encode()
    Map<String, String> carrier = [(PROPAGATION_KEY_BASE64): encoded, "someotherkey": "someothervalue"]
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(fromTags(DataStreamsTags.create("sqs", DataStreamsTags.Direction.OUTBOUND, "topic", null, null)), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      tags.direction == "direction:out"
      tags.topic == "topic:topic"
      tags.type == "type:sqs"
      tags.nonNullSize() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.encode()
    carrier = [(PROPAGATION_KEY_BASE64): secondEncode]
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, null)
    timeSource.advance(MILLISECONDS.toNanos(30))
    context.setCheckpoint(fromTags(DataStreamsTags.create("sqs", DataStreamsTags.Direction.INBOUND, "topicB", null, null)), pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      tags.type == "type:sqs"
      tags.topic == "topic:topicB"
      tags.nonNullSize() == 3
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Empty tags not set"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, null)

    when:
    timeSource.advance(50)
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint(fromTags(DataStreamsTags.create("type", DataStreamsTags.Direction.OUTBOUND, "topic", "group", null)), pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint(fromTags(DataStreamsTags.create(null, null)), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 3
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      tags.type ==  "type:type"
      tags.topic == "topic:topic"
      tags.group == "group:group"
      tags.direction == "direction:out"
      tags.nonNullSize() == 4
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
    with(pointConsumer.points[2]) {
      tags.nonNullSize() == 0
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == 50
      edgeLatencyNano == 25
    }
  }

  def "Check context extractor decorator behavior"() {
    given:
    def sink = Mock(Sink)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)

    def globalTraceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> { return dynamicConfigEnabled }
    }

    def tracerApi = Mock(AgentTracer.TracerAPI) {
      captureTraceConfig() >> globalTraceConfig
    }
    AgentTracer.TracerAPI originalTracer = AgentTracer.get()
    AgentTracer.forceRegister(tracerApi)

    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { globalTraceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)

    BaseHash.updateBaseHash(baseHash)
    def context = new DefaultPathwayContext(timeSource, null)
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)
    def encoded = context.encode()
    Map<String, String> carrier = [
      (PROPAGATION_KEY_BASE64): encoded,
      "someotherkey": "someothervalue"
    ]
    def contextVisitor = new Base64MapContextVisitor()
    def propagator = dataStreams.propagator()

    when:
    def extractedContext = propagator.extract(root(), carrier, contextVisitor)
    def extractedSpan = AgentSpan.fromContext(extractedContext)

    then:
    encoded == "L+lDG/Pa9hRkZA=="
    !dynamicConfigEnabled || extractedSpan != null
    if (dynamicConfigEnabled) {
      def extracted = extractedSpan.context()
      assert extracted != null
      assert extracted.pathwayContext != null
      assert extracted.pathwayContext.isStarted()
    }

    cleanup:
    AgentTracer.forceRegister(originalTracer)

    where:
    dynamicConfigEnabled << [true, false]
  }

  def "Check context extractor decorator behavior when trace data is null"() {
    given:
    def sink = Mock(Sink)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)

    def globalTraceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> { return globalDsmEnabled }
    }

    def tracerApi = Mock(AgentTracer.TracerAPI) {
      captureTraceConfig() >> globalTraceConfig
    }
    AgentTracer.TracerAPI originalTracer = AgentTracer.get()
    AgentTracer.forceRegister(tracerApi)

    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { globalTraceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)

    BaseHash.updateBaseHash(baseHash)
    def context = new DefaultPathwayContext(timeSource, null)
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)
    def encoded = context.encode()

    Map<String, String> carrier = [(PROPAGATION_KEY_BASE64): encoded, "someotherkey": "someothervalue"]
    def contextVisitor = new Base64MapContextVisitor()
    def propagator = dataStreams.propagator()

    when:
    def extractedContext = propagator.extract(root(), carrier, contextVisitor)
    def extractedSpan = AgentSpan.fromContext(extractedContext)

    then:
    encoded == "L+lDG/Pa9hRkZA=="
    if (globalDsmEnabled) {
      extractedSpan != null
      def extracted = extractedSpan.context()
      extracted != null
      extracted.pathwayContext != null
      extracted.pathwayContext.isStarted()
    } else {
      extractedSpan == null
    }

    cleanup:
    AgentTracer.forceRegister(originalTracer)

    where:
    globalDsmEnabled << [true, false]
  }

  def "Check context extractor decorator behavior when local trace config is null"() {
    given:
    def sink = Mock(Sink)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)

    def globalTraceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> { return globalDsmEnabled }
    }

    def tracerApi = Mock(AgentTracer.TracerAPI) {
      captureTraceConfig() >> globalTraceConfig
    }
    AgentTracer.TracerAPI originalTracer = AgentTracer.get()
    AgentTracer.forceRegister(tracerApi)

    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { globalTraceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)

    BaseHash.updateBaseHash(baseHash)
    def context = new DefaultPathwayContext(timeSource, null)
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(fromTags(DataStreamsTags.create("internal", DataStreamsTags.Direction.INBOUND)), pointConsumer)
    def encoded = context.encode()
    Map<String, String> carrier = [(PROPAGATION_KEY_BASE64): encoded, "someotherkey": "someothervalue"]
    def contextVisitor = new Base64MapContextVisitor()
    def spanContext = new ExtractedContext(DDTraceId.ONE, 1, 0, null, 0,
      null, (TagMap)null, null, null, globalTraceConfig, DATADOG)
    def baseContext = AgentSpan.fromSpanContext(spanContext).storeInto(root())
    def propagator = dataStreams.propagator()

    when:
    def extractedContext = propagator.extract(baseContext, carrier, contextVisitor)
    def extractedSpan = AgentSpan.fromContext(extractedContext)

    then:
    extractedSpan != null

    when:
    def extracted = extractedSpan.context()

    then:
    extracted != null
    encoded == "L+lDG/Pa9hRkZA=="
    if (globalDsmEnabled) {
      extracted.pathwayContext != null
      extracted.pathwayContext.isStarted()
    } else {
      extracted.pathwayContext == null
    }

    cleanup:
    AgentTracer.forceRegister(originalTracer)

    where:
    globalDsmEnabled << [true, false]
  }

  def "Check context extractor decorator behavior when trace data and dsm data are null"() {
    given:
    def sink = Mock(Sink)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)

    Map<String, String> carrier = ["someotherkey": "someothervalue"]
    def contextVisitor = new Base64MapContextVisitor()
    def propagator = dataStreams.propagator()

    when:
    def extractedContext = propagator.extract(root(), carrier, contextVisitor)
    def extractedSpan = AgentSpan.fromContext(extractedContext)

    then:
    extractedSpan == null
  }

  class Base64MapContextVisitor implements AgentPropagation.ContextVisitor<Map<String, String>> {
    @Override
    void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, String> entry : carrier.entrySet()) {
        classifier.accept(entry.key, entry.value)
      }
    }
  }
}

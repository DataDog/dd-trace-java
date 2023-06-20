package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.DDTraceId
import datadog.trace.api.WellKnownTags
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.PathwayContext
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.common.metrics.Sink
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.HttpCodec
import datadog.trace.core.test.DDCoreSpecification

import java.util.function.Consumer

import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoring.DEFAULT_BUCKET_DURATION_NANOS
import static java.util.concurrent.TimeUnit.MILLISECONDS

class DefaultPathwayContextTest extends DDCoreSpecification {
  def wellKnownTags = new WellKnownTags("runtimeid", "hostname", "testing", "service", "version", "java")

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
  }

  def "First Set checkpoint starts the context."() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(50)
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    verifyFirstPoint(pointConsumer.points[0])
  }

  def "Checkpoint generated"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(50)
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint(
      new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 2
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
  }

  def "Multiple checkpoints generated"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(50)
    context.setCheckpoint(new LinkedHashMap<>(["direction": "out", "type": "kafka"]), pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint(
      new LinkedHashMap<>(["direction": "in", "group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)
    timeSource.advance(30)
    context.setCheckpoint(
      new LinkedHashMap<>(["direction": "in", "group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 3
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      edgeTags == ["direction:in", "group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 4
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
    with(pointConsumer.points[2]) {
      edgeTags == ["direction:in", "group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 4
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
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    context.encode()

    then:
    thrown(IllegalStateException)
  }

  def "Encoding and decoding (base64) a context"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)
    def encoded = context.strEncode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def decodedContext = DefaultPathwayContext.strDecode(timeSource, wellKnownTags, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2

    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(27)
      edgeLatencyNano == MILLISECONDS.toNanos(27)
    }
  }

  def "Encoding and decoding (base64) with contexts and checkpoints"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    def encoded = context.strEncode()
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.strDecode(timeSource, wellKnownTags, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.strEncode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.strDecode(timeSource, wellKnownTags, secondEncode)
    timeSource.advance(MILLISECONDS.toNanos(30))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topicB", "type": "kafka"]), pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      edgeTags == ["group:group", "topic:topicB", "type:kafka"]
      edgeTags.size() == 3
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
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)
    def contextVisitor = new Base64MapContextVisitor()

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    def encoded = context.strEncode()
    Map<String, String> carrier = [(PathwayContext.PROPAGATION_KEY_BASE64): encoded, "someotherkey": "someothervalue"]
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, wellKnownTags)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.strEncode()
    carrier = [(PathwayContext.PROPAGATION_KEY_BASE64): secondEncode]
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, wellKnownTags)
    timeSource.advance(MILLISECONDS.toNanos(30))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topicB", "type": "kafka"]), pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      edgeTags == ["group:group", "topic:topicB", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Encoding and decoding (binary) a context"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)
    def encoded = context.encode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def decodedContext = DefaultPathwayContext.decode(timeSource, wellKnownTags, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2

    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(27)
      edgeLatencyNano == MILLISECONDS.toNanos(27)
    }
  }

  def "Encoding and decoding (binary) with contexts and checkpoints"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    def encoded = context.encode()
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.decode(timeSource, wellKnownTags, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.encode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.decode(timeSource, wellKnownTags, secondEncode)
    timeSource.advance(MILLISECONDS.toNanos(30))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topicB", "type": "kafka"]), pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      edgeTags == ["group:group", "topic:topicB", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Legacy binary encoding"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)
    def contextVisitor = new BinaryMapContextVisitor()

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    def encoded = java.util.Base64.getDecoder().decode(context.encode())
    Map<String, byte[]> carrier = [(PathwayContext.PROPAGATION_KEY): encoded]
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.extractBinary(carrier, contextVisitor, timeSource, wellKnownTags)

    then:
    decodedContext.strEncode() == context.strEncode()
  }

  def "Encoding and decoding (binary) with injects and extracts"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)
    def contextVisitor = new BinaryMapContextVisitor()

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    def encoded = context.encode()
    Map<String, byte[]> carrier = [(PathwayContext.PROPAGATION_KEY_BASE64): encoded, "someotherkey": new byte[0]]
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.extractBinary(carrier, contextVisitor, timeSource, wellKnownTags)
    timeSource.advance(MILLISECONDS.toNanos(25))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "kafka"]), pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.encode()
    carrier = [(PathwayContext.PROPAGATION_KEY_BASE64): secondEncode]
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.extractBinary(carrier, contextVisitor, timeSource, wellKnownTags)
    timeSource.advance(MILLISECONDS.toNanos(30))
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topicB", "type": "kafka"]), pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      edgeTags == ["group:group", "topic:topicB", "type:kafka"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Empty tags not set"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(50)
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint(new LinkedHashMap<>(["group": "group", "topic": "topic", "type": "type"]), pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint(new LinkedHashMap<>(), pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 3
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      edgeTags == ["group:group", "topic:topic", "type:type"]
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
    with(pointConsumer.points[2]) {
      edgeTags.size() == 0
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == 50
      edgeLatencyNano == 25
    }
  }

  def "Primary tag used in hash calculation"() {
    given:
    def timeSource = new ControllableTimeSource()

    when:
    def firstContext = new DefaultPathwayContext(timeSource, wellKnownTags)
    timeSource.advance(50)
    firstContext.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    injectSysConfig(PRIMARY_TAG, "region-2")
    def secondContext = new DefaultPathwayContext(timeSource, wellKnownTags)
    timeSource.advance(25)
    secondContext.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)

    then:
    pointConsumer.points.size() == 2
    verifyFirstPoint(pointConsumer.points[0])
    verifyFirstPoint(pointConsumer.points[1])
    pointConsumer.points[0].hash != pointConsumer.points[1].hash
  }

  def "Check context extractor decorator behavior"() {
    given:
    def sink = Mock(Sink)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)

    def context = new DefaultPathwayContext(timeSource, wellKnownTags)
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(new LinkedHashMap<>(["type": "internal"]), pointConsumer)
    def encoded = context.strEncode()
    Map<String, String> carrier = [(PathwayContext.PROPAGATION_KEY_BASE64): encoded, "someotherkey": "someothervalue"]
    def contextVisitor = new Base64MapContextVisitor()
    def extractor = new FakeExtractor()
    def decorated = dataStreams.decorate(extractor)

    when:
    def extracted = decorated.extract(carrier, contextVisitor)

    then:
    extracted != null
    extracted.pathwayContext != null
    extracted.pathwayContext.isStarted()
  }

  class FakeExtractor implements HttpCodec.Extractor {
    @Override
    <C> TagContext extract(C carrier, AgentPropagation.ContextVisitor<C> getter) {
      return new ExtractedContext(DDTraceId.ONE, 1, 0, null, null)
    }
  }

  class Base64MapContextVisitor implements AgentPropagation.ContextVisitor<Map<String, String>> {
    @Override
    void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, String> entry : carrier.entrySet()) {
        classifier.accept(entry.key, entry.value)
      }
    }
  }

  class BinaryMapContextVisitor extends  Base64MapContextVisitor implements AgentPropagation.BinaryContextVisitor<Map<String, byte[]>> {
    @Override
    void forEachKey(Map<String, byte[]> carrier, AgentPropagation.BinaryKeyClassifier classifier) {
      for (Map.Entry<String, byte[]> entry : carrier.entrySet()) {
        classifier.accept(entry.key, entry.value)
      }
    }
  }
}

package datadog.trace.core.datastreams

import datadog.trace.api.WellKnownTags
import datadog.trace.api.function.Consumer
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.PathwayContext
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Requires

import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG
import static java.util.concurrent.TimeUnit.MILLISECONDS

@Requires({
  jvm.isJava8Compatible()
})
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
    assert point.edgeTags.isEmpty()
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
    context.setCheckpoint("kafka", "", "topic", pointConsumer)

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
    context.setCheckpoint(null, null, null, pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint("kafka", "group", "topic", pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 2
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topic"])
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
    context.setCheckpoint(null, null, null, pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint("kafka", "group", "topic", pointConsumer)
    timeSource.advance(30)
    context.setCheckpoint("kafka", "group", "topic", pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 3
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topic"])
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
    with(pointConsumer.points[2]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topic"])
      edgeTags.size() == 3
      parentHash == pointConsumer.points[1].hash
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

  def "Encoding and decoding a context"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(null, null, null, pointConsumer)
    def encoded = context.strEncode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def decodedContext = DefaultPathwayContext.strDecode(timeSource, wellKnownTags, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    decodedContext.setCheckpoint("kafka", "group", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2

    with(pointConsumer.points[1]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topic"])
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(27)
      edgeLatencyNano == MILLISECONDS.toNanos(27)
    }
  }

  def "Encoding and decoding with contexts and checkpoints"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(null, null, null, pointConsumer)

    def encoded = context.strEncode()
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.strDecode(timeSource, wellKnownTags, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    decodedContext.setCheckpoint("kafka", "group", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topic"])
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
    secondDecode.setCheckpoint("kafka", "group", "topicB", pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topicB"])
      edgeTags.size() == 3
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Encoding and decoding with injects and extracts"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)
    def contextVisitor = new MapContextVisitor()

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.setCheckpoint(null, null, null, pointConsumer)

    def encoded = context.strEncode()
    Map<String, String> carrier = [(PathwayContext.PROPAGATION_KEY): encoded, "someotherkey": "someothervalue"]
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, wellKnownTags)
    timeSource.advance(MILLISECONDS.toNanos(25))
    decodedContext.setCheckpoint("kafka", "group", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topic"])
      edgeTags.size() == 3
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.strEncode()
    carrier = [(PathwayContext.PROPAGATION_KEY): secondEncode]
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource, wellKnownTags)
    timeSource.advance(MILLISECONDS.toNanos(30))
    secondDecode.setCheckpoint("kafka", "group", "topicB", pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      edgeTags.containsAll(["type:kafka", "group:group", "topic:topicB"])
      edgeTags.size() == 3
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  def "Empty and null tags not set"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource, wellKnownTags)

    when:
    timeSource.advance(50)
    context.setCheckpoint(null, null, null, pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint(type, group, topic, pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 2
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      edgeTags.containsAll(tags)
      edgeTags.size() == tags.size()
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }

    where:
    type    | group   | topic   | tags
    "kafka" | "group" | "topic" | ["type:kafka", "group:group", "topic:topic"]
    ""      | "group" | "topic" | ["group:group", "topic:topic"]
    null    | "group" | "topic" | ["group:group", "topic:topic"]
    "kafka" | ""      | "topic" | ["type:kafka", "topic:topic"]
    "kafka" | null    | "topic" | ["type:kafka", "topic:topic"]
    ""      | ""      | "topic" | ["topic:topic"]
    null    | null    | "topic" | ["topic:topic"]
  }

  def "Primary tag used in hash calculation"() {
    given:
    def timeSource = new ControllableTimeSource()

    when:
    def firstContext = new DefaultPathwayContext(timeSource, wellKnownTags)
    timeSource.advance(50)
    firstContext.setCheckpoint(null, null, null, pointConsumer)

    injectSysConfig(PRIMARY_TAG, "region-2")
    def secondContext = new DefaultPathwayContext(timeSource, wellKnownTags)
    timeSource.advance(25)
    secondContext.setCheckpoint(null, null, null, pointConsumer)

    then:
    pointConsumer.points.size() == 2
    verifyFirstPoint(pointConsumer.points[0])
    verifyFirstPoint(pointConsumer.points[1])
    pointConsumer.points[0].hash != pointConsumer.points[1].hash
  }

  class MapContextVisitor implements AgentPropagation.ContextVisitor<Map<String, String>> {
    @Override
    void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, String> entry : carrier.entrySet()) {
        classifier.accept(entry.key, entry.value)
      }
    }
  }
}

package datadog.trace.core.datastreams

import datadog.trace.api.function.Consumer
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.PathwayContext
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Requires

import static java.util.concurrent.TimeUnit.MILLISECONDS

@Requires({
  jvm.isJava8Compatible()
})
class DefaultPathwayContextTest extends DDCoreSpecification {
  def pointConsumer = new Consumer<StatsPoint>() {
    List<StatsPoint> points = []

    @Override
    void accept(StatsPoint point) {
      points.add(point)
    }
  }

  void verifyFirstPoint(StatsPoint point) {
    assert point.topic == ""
    assert point.type == null
    assert point.group == null
    assert point.parentHash == 0
    assert point.pathwayLatencyNano == 0
    assert point.edgeLatencyNano == 0
  }

  def "StatsPoint emitted when start called"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource)

    when:
    timeSource.advance(50)
    context.start(pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    verifyFirstPoint(pointConsumer.points[0])
  }

  def "StatsPoint not emitted when start called more than once"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource)

    when:
    timeSource.advance(50)
    context.start(pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1

    when:
    timeSource.advance(50)
    context.start(pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    verifyFirstPoint(pointConsumer.points[0])
  }

  def "Checkpoint converted to start on unstarted context"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource)

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
    def context = new DefaultPathwayContext(timeSource)

    when:
    timeSource.advance(50)
    context.start(pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint("kafka", "group", "topic", pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 2
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      topic == "topic"
      type == "kafka"
      group == "group"
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
  }

  def "Multiple checkpoints generated"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource)

    when:
    timeSource.advance(50)
    context.start(pointConsumer)
    timeSource.advance(25)
    context.setCheckpoint("kafka", "group", "topic", pointConsumer)
    timeSource.advance(30)
    context.setCheckpoint("kafka", "group", "topic", pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 3
    verifyFirstPoint(pointConsumer.points[0])
    with(pointConsumer.points[1]) {
      topic == "topic"
      type == "kafka"
      group == "group"
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == 25
      edgeLatencyNano == 25
    }
    with(pointConsumer.points[2]) {
      topic == "topic"
      type == "kafka"
      group == "group"
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == 55
      edgeLatencyNano == 30
    }
  }

  def "Exception thrown when trying to encode unstarted context"() {
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource)

    when:
    context.encode()

    then:
    thrown(IllegalStateException)
  }

  def "Encoding and decoding a context"() {
    // Timesource needs to be advanced in milliseconds because encoding truncates to millis
    given:
    def timeSource = new ControllableTimeSource()
    def context = new DefaultPathwayContext(timeSource)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.start(pointConsumer)
    def encoded = context.encode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def decodedContext = DefaultPathwayContext.decode(timeSource, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    decodedContext.setCheckpoint("kafka", "", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2

    with(pointConsumer.points[1]) {
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
    def context = new DefaultPathwayContext(timeSource)

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.start(pointConsumer)

    def encoded = context.encode()
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.decode(timeSource, encoded)
    timeSource.advance(MILLISECONDS.toNanos(25))
    decodedContext.setCheckpoint("kafka", "", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.encode()
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.decode(timeSource, secondEncode)
    timeSource.advance(MILLISECONDS.toNanos(30))
    secondDecode.setCheckpoint("kafka", "", "topicB", pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
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
    def context = new DefaultPathwayContext(timeSource)
    def contextVisitor = new MapContextVisitor()

    when:
    timeSource.advance(MILLISECONDS.toNanos(50))
    context.start(pointConsumer)

    def encoded = context.encode()
    Map<String, byte[]> carrier = [(PathwayContext.PROPAGATION_KEY): encoded, "someotherkey": new byte[0]]
    timeSource.advance(MILLISECONDS.toNanos(1))
    def decodedContext = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource)
    timeSource.advance(MILLISECONDS.toNanos(25))
    decodedContext.setCheckpoint("kafka", "", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    with(pointConsumer.points[1]) {
      parentHash == pointConsumer.points[0].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(26)
      edgeLatencyNano == MILLISECONDS.toNanos(26)
    }

    when:
    def secondEncode = decodedContext.encode()
    carrier = [(PathwayContext.PROPAGATION_KEY): secondEncode]
    timeSource.advance(MILLISECONDS.toNanos(2))
    def secondDecode = DefaultPathwayContext.extract(carrier, contextVisitor, timeSource)
    timeSource.advance(MILLISECONDS.toNanos(30))
    secondDecode.setCheckpoint("kafka", "", "topicB", pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    with(pointConsumer.points[2]) {
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano == MILLISECONDS.toNanos(58)
      edgeLatencyNano == MILLISECONDS.toNanos(32)
    }
  }

  class MapContextVisitor implements AgentPropagation.BinaryContextVisitor<Map<String, byte[]>> {
    @Override
    void forEachKey(Map<String, byte[]> carrier, AgentPropagation.BinaryKeyClassifier classifier) {
      for (Map.Entry<String, byte[]> entry: carrier.entrySet()) {
        classifier.accept(entry.key, entry.value)
      }
    }
  }
}

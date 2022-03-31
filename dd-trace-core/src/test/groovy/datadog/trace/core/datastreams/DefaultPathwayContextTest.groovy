package datadog.trace.core.datastreams

import datadog.trace.api.function.Consumer
import datadog.trace.api.time.SystemTimeSource
import datadog.trace.api.time.TimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Requires

@Requires({ jvm.isJava8Compatible() })
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
    def context = new DefaultPathwayContext(SystemTimeSource.INSTANCE)

    when:
    context.start(pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    verifyFirstPoint(pointConsumer.points[0])
  }

  def "StatsPoint not emitted when start called more than once"() {
    given:
    def context = new DefaultPathwayContext(SystemTimeSource.INSTANCE)

    when:
    context.start(pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1

    when:
    context.start(pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    verifyFirstPoint(pointConsumer.points[0])
  }

  def "Checkpoint converted to start on unstarted context"() {
    given:
    def context = new DefaultPathwayContext(SystemTimeSource.INSTANCE)

    when:
    context.setCheckpoint("kafka", "", "topic", pointConsumer)

    then:
    context.isStarted()
    pointConsumer.points.size() == 1
    verifyFirstPoint(pointConsumer.points[0])
  }

  def "Checkpoint generated"() {
    given:
    def context = new DefaultPathwayContext(SystemTimeSource.INSTANCE)

    when:
    context.start(pointConsumer)
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
      pathwayLatencyNano != 0
      edgeLatencyNano != 0
    }
  }

  def "Multiple checkpoints generated"() {
    given:
    def context = new DefaultPathwayContext(SystemTimeSource.INSTANCE)

    when:
    context.start(pointConsumer)
    context.setCheckpoint("kafka", "group", "topic", pointConsumer)
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
      pathwayLatencyNano != 0
      edgeLatencyNano != 0
    }
    with(pointConsumer.points[2]) {
      topic == "topic"
      type == "kafka"
      group == "group"
      parentHash == pointConsumer.points[1].hash
      hash != 0
      pathwayLatencyNano != 0
      edgeLatencyNano != 0
    }
  }

  def "Exception thrown when trying to encode unstarted context"() {
    given:
    def context = new DefaultPathwayContext(SystemTimeSource.INSTANCE)

    when:
    context.encode()

    then:
    thrown(IllegalStateException)
  }

  def "Encoding and decoding a context"() {
    given:
    def timeSource = Mock(TimeSource)
    def context = new DefaultPathwayContext(timeSource)

    when:
    context.start(pointConsumer)
    def encoded = context.encode()
    def decodedContext = DefaultPathwayContext.decode(timeSource, encoded)
    decodedContext.setCheckpoint("kafka", "", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    pointConsumer.points[1].parentHash == pointConsumer.points[0].hash
    pointConsumer.points[1].hash != 0
  }

  def "Encoding and decoding with contexts and checkpoints"() {
    given:
    def timeSource = Mock(TimeSource)
    def context = new DefaultPathwayContext(timeSource)

    when:
    context.start(pointConsumer)

    def encoded = context.encode()
    def decodedContext = DefaultPathwayContext.decode(timeSource, encoded)
    decodedContext.setCheckpoint("kafka", "", "topic", pointConsumer)

    then:
    decodedContext.isStarted()
    pointConsumer.points.size() == 2
    pointConsumer.points[1].parentHash == pointConsumer.points[0].hash
    pointConsumer.points[1].hash != 0

    when:
    def secondEncode = decodedContext.encode()
    def secondDecode = DefaultPathwayContext.decode(timeSource, secondEncode)
    secondDecode.setCheckpoint("kafka", "", "topicB", pointConsumer)

    then:
    secondDecode.isStarted()
    pointConsumer.points.size() == 3
    pointConsumer.points[2].parentHash == pointConsumer.points[1].hash
    pointConsumer.points[2].hash != 0
  }
}

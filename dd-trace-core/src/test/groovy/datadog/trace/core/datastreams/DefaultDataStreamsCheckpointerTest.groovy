package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.WellKnownTags
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.Sink
import datadog.trace.core.test.DDCoreSpecification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.DEFAULT_BUCKET_DURATION_NANOS
import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.FEATURE_CHECK_INTERVAL_NANOS
import static java.util.concurrent.TimeUnit.SECONDS

class DefaultDataStreamsCheckpointerTest extends DDCoreSpecification {
  def wellKnownTags = new WellKnownTags("runtimeid", "hostname", "testing", "service", "version", "java")

  def "No payloads written if data streams not supported"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> false
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)
    def sink = Mock(Sink)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 0, 0, timeSource.currentTimeNanos, 0, 0))
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
    }
    0 * payloadWriter.writePayload(_)

    cleanup:
    checkpointer.close()
  }

  def "Write group after a delay"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
      }
    }

    cleanup:
    payloadWriter.close()
    checkpointer.close()
  }

  // This test relies on automatic reporting instead of manually calling report
  def "SLOW Write group after a delay"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()
    def bucketDuration = TimeUnit.MILLISECONDS.toNanos(200)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, bucketDuration)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(bucketDuration)

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
      }
    }

    cleanup:
    payloadWriter.close()
    checkpointer.close()
  }

  def "Groups for current bucket are not reported"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 3, 4, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
      }
    }

    cleanup:
    payloadWriter.close()
    checkpointer.close()
  }

  def "All groups written in close"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    checkpointer.close()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
      }
    }

    with(payloadWriter.buckets.get(1)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic2"])
        edgeTags.size() == 3
        hash == 3
        parentHash == 4
      }
    }

    cleanup:
    payloadWriter.close()
  }

  def "Groups from multiple buckets are reported"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
      }
    }

    with(payloadWriter.buckets.get(1)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic2"])
        edgeTags.size() == 3
        hash == 3
        parentHash == 4
      }
    }

    cleanup:
    payloadWriter.close()
    checkpointer.close()
  }

  def "Multiple points are correctly grouped in multiple buckets"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(10), SECONDS.toNanos(10)))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(5), SECONDS.toNanos(5)))
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, SECONDS.toNanos(2), 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
        pathwayLatency.getMaxValue() >= 10
        pathwayLatency.getMaxValue() < 10.1
      }
    }

    with(payloadWriter.buckets.get(1)) {
      groups.size() == 2

      List<StatsGroup> sortedGroups = new ArrayList<>(groups)
      sortedGroups.sort({ it.hash })

      with(sortedGroups[0]) {
        hash == 1
        parentHash == 2
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        pathwayLatency.getMaxValue() >= 5
        pathwayLatency.getMaxValue() < 5.1
      }

      with(sortedGroups[1]) {
        hash == 3
        parentHash == 4
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic2"])
        edgeTags.size() == 3
        pathwayLatency.getMaxValue() >= 2
        pathwayLatency.getMaxValue() < 2.1
      }
    }

    cleanup:
    payloadWriter.close()
    checkpointer.close()
  }

  def "feature upgrade"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    boolean supportsDataStreaming = false
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> { return supportsDataStreaming }
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when: "reporting points when data streams is not supported"
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "report called multiple times without advancing past check interval"
    checkpointer.report()
    checkpointer.report()
    checkpointer.report()

    then: "features are not rechecked"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
    }
    0 * features.discover()
    payloadWriter.buckets.isEmpty()

    when: "submitting points after an upgrade"
    supportsDataStreaming = true
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS)
    checkpointer.report()

    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then: "points are now reported"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
      }
    }

    cleanup:
    payloadWriter.close()
    checkpointer.close()
  }

  def "feature downgrade then upgrade"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    boolean supportsDataStreaming = true
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> { return supportsDataStreaming }
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when: "reporting points after a downgrade"
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    supportsDataStreaming = false
    checkpointer.onEvent(EventListener.EventType.DOWNGRADED, "")
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "submitting points after an upgrade"
    supportsDataStreaming = true
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS)
    checkpointer.report()

    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then: "points are now reported"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        hash == 1
        parentHash == 2
      }
    }

    cleanup:
    payloadWriter.close()
    checkpointer.close()
  }
}

class CapturingPayloadWriter implements DatastreamsPayloadWriter {
  boolean accepting = true
  List<StatsBucket> buckets = new ArrayList<>()

  void writePayload(Collection<StatsBucket> payload) {
    if (accepting) {
      buckets.addAll(payload)
    }
  }

  void close() {
    // Stop accepting new buckets so any late submissions by the reporting thread aren't seen
    accepting = false
  }
}

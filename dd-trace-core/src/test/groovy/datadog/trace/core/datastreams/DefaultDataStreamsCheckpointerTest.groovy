package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.WellKnownTags
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.Sink
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Requires
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.DEFAULT_BUCKET_DURATION_NANOS
import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.FEATURE_CHECK_INTERVAL_NANOS
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({
  jvm.isJava8Compatible()
})
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
    timeSource.advance(SECONDS.toNanos(30))
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
      assert payloadWriter.bucketTypes.size() == 2
      assert payloadWriter.bucketTypes.containsAll([StatsBucket.BucketType.TIMESTAMP_CURRENT, StatsBucket.BucketType.TIMESTAMP_ORIGIN])
      assert payloadWriter.buckets.get(0).getStartTimeNanos() != payloadWriter.buckets.get(1).getStartTimeNanos()
    }

    payloadWriter.buckets.each {
      it.groups.size() == 1

      with(it.groups.iterator().next()) {
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
    timeSource.advance(SECONDS.toNanos(30))
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()
    def bucketDuration = TimeUnit.MILLISECONDS.toNanos(200)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, bucketDuration)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(bucketDuration)

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
      assert payloadWriter.bucketTypes.size() == 2
      assert payloadWriter.bucketTypes.containsAll([StatsBucket.BucketType.TIMESTAMP_CURRENT, StatsBucket.BucketType.TIMESTAMP_ORIGIN])
      assert payloadWriter.buckets.get(0).getStartTimeNanos() != payloadWriter.buckets.get(1).getStartTimeNanos()
    }

    payloadWriter.buckets.each {
      it.groups.size() == 1

      with(it.groups.iterator().next()) {
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
    timeSource.advance(SECONDS.toNanos(30))
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 3, 4, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 3
      assert payloadWriter.bucketTypes.size() == 3
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_CURRENT } == 1
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_ORIGIN } == 2
    }

    payloadWriter.buckets.eachWithIndex { it, index ->
      it.groups.size() == 1
      long startTimeNano = it.getStartTimeNanos()

      with(it.groups.iterator().next()) {
        edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
        edgeTags.size() == 3
        if (payloadWriter.bucketTypes.get(index) == StatsBucket.BucketType.TIMESTAMP_CURRENT) {
          hash == 1
          parentHash == 2
        } else if (startTimeNano == SECONDS.toNanos(30) - SECONDS.toNanos(20)) {
          // Origin-based bucket for first point
          hash == 1
          parentHash == 2
        } else {
          hash == 3
          parentHash == 4
        }
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
    timeSource.advance(SECONDS.toNanos(30))
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    checkpointer.close()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 4
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_CURRENT } == 2
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_ORIGIN } == 2
    }

    payloadWriter.buckets.eachWithIndex { it, index ->
      it.groups.size() == 1
      long startTimeNano = it.getStartTimeNanos()

      with(it.groups.iterator().next()) {
        edgeTags.size() == 3
        if (startTimeNano == SECONDS.toNanos(30) - SECONDS.toNanos(20) ||
          startTimeNano == SECONDS.toNanos(30)) {
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
          hash == 1
          parentHash == 2
        } else {
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic2"])
          hash == 3
          parentHash == 4
        }
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
    timeSource.advance(SECONDS.toNanos(30))
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.accept(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, SECONDS.toNanos(20), 20))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 4
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_CURRENT } == 2
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_ORIGIN } == 2
    }

    payloadWriter.buckets.eachWithIndex { it, index ->
      it.groups.size() == 1
      long startTimeNano = it.getStartTimeNanos()

      with(it.groups.iterator().next()) {
        edgeTags.size() == 3
        if (startTimeNano == SECONDS.toNanos(30) - SECONDS.toNanos(20) ||
          startTimeNano == SECONDS.toNanos(30)) {
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
          hash == 1
          parentHash == 2
        } else {
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic2"])
          hash == 3
          parentHash == 4
        }
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
    timeSource.advance(SECONDS.toNanos(30))
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    checkpointer.start()
    /*
     * origin        current        topic
     * 30s           30s            testTopic
     * 29s9...900ns  39s9...900ns   testTopic
     * 44s9...900ns  49s9...900ns   testTopic
     * 47s9...900ns  49s9...900ns   testTopic2
     */
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
      assert payloadWriter.buckets.size() == 5
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_CURRENT } == 2
      assert payloadWriter.bucketTypes.count { it == StatsBucket.BucketType.TIMESTAMP_ORIGIN } == 3
    }

    payloadWriter.buckets.eachWithIndex { it, index ->
      it.groups.size() == 1
      long startTimeNano = it.getStartTimeNanos()

      if (startTimeNano == SECONDS.toNanos(20)) {
        with(it.groups.iterator().next()) {
          edgeTags.size() == 3
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
          hash == 1
          parentHash == 2
          payloadWriter.bucketTypes.get(index) == StatsBucket.BucketType.TIMESTAMP_ORIGIN
        }
      } else if (startTimeNano == SECONDS.toNanos(30) && payloadWriter.bucketTypes.get(index) == StatsBucket.BucketType.TIMESTAMP_ORIGIN) {
        with(it.groups.iterator().next()) {
          edgeTags.size() == 3
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
          hash == 1
          parentHash == 2
        }
      } else if (startTimeNano == SECONDS.toNanos(30) && payloadWriter.bucketTypes.get(index) == StatsBucket.BucketType.TIMESTAMP_CURRENT) {
        with(it.groups.iterator().next()) {
          edgeTags.size() == 3
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
          hash == 1
          parentHash == 2
          pathwayLatency.max() >= 10
          pathwayLatency.max() < 10.1
        }
      } else if (startTimeNano == SECONDS.toNanos(40)) {
        it.groups.size() == 2

        List<StatsGroup> sortedGroups = new ArrayList<>(it.groups)
        sortedGroups.sort({ it.hash })

        with(sortedGroups[0]) {
          hash == 1
          parentHash == 2
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic"])
          edgeTags.size() == 3
          pathwayLatency.max() >= 5
          pathwayLatency.max() < 5.1
        }

        with(sortedGroups[1]) {
          hash == 3
          parentHash == 4
          edgeTags.containsAll(["type:testType", "group:testGroup", "topic:testTopic2"])
          edgeTags.size() == 3
          pathwayLatency.max() >= 2
          pathwayLatency.max() < 2.1
        }
      } else {
        assert false : "Unexpected bucket: " + it.toString()
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
      assert payloadWriter.buckets.size() == 2
    }

    payloadWriter.buckets.each {
      it.groups.size() == 1

      with(it.groups.iterator().next()) {
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
      assert payloadWriter.buckets.size() == 2
    }

    payloadWriter.buckets.each {
      it.groups.size() == 1

      with(it.groups.iterator().next()) {
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
  List<StatsBucket.BucketType> bucketTypes = new ArrayList<>()

  void writePayload(Collection<StatsBucket> payload, StatsBucket.BucketType bucketType) {
    if (accepting) {
      buckets.addAll(payload)
      bucketTypes.addAll([bucketType] * payload.size())
    }
  }

  void close() {
    // Stop accepting new buckets so any late submissions by the reporting thread aren't seen
    accepting = false
  }
}

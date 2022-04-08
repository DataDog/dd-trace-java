package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.Sink
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Requires
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.DEFAULT_BUCKET_DURATION_MILLIS
import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.FEATURE_CHECK_INTERVAL_MILLIS
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({
  jvm.isJava8Compatible()
})
class DefaultDataStreamsCheckpointerTest extends DDCoreSpecification {
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("test", "test", "test", 0, 0, timeSource.currentTimeMillis, 0, 0))
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
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
    def bucketDuration = 200

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, bucketDuration)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(bucketDuration))

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS - 100l))
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS - 100l))
    checkpointer.close()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
        hash == 1
        parentHash == 2
      }
    }

    with(payloadWriter.buckets.get(1))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic2"
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
        hash == 1
        parentHash == 2
      }
    }

    with(payloadWriter.buckets.get(1))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic2"
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS - 100l))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, SECONDS.toNanos(10), SECONDS.toNanos(10)))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, SECONDS.toNanos(5), SECONDS.toNanos(5)))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, SECONDS.toNanos(2), 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 2
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
        hash == 1
        parentHash == 2
        pathwayLatency.max() >= 10
        pathwayLatency.max() < 10.1
      }
    }

    with(payloadWriter.buckets.get(1))  {
      groups.size() == 2

      List<StatsGroup> sortedGroups = new ArrayList<>(groups)
      sortedGroups.sort({ it.topic })

      with (sortedGroups[0]) {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
        hash == 1
        parentHash == 2
        pathwayLatency.max() >= 5
        pathwayLatency.max() < 5.1
      }

      with (sortedGroups[1])  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic2"
        hash == 3
        parentHash == 4
        pathwayLatency.max() >= 2
        pathwayLatency.max() < 2.1
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
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
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(FEATURE_CHECK_INTERVAL_MILLIS))
    checkpointer.report()

    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()

    then: "points are now reported"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
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
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.start()
    supportsDataStreaming = false
    checkpointer.onEvent(EventListener.EventType.DOWNGRADED, "")
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert checkpointer.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "submitting points after an upgrade"
    supportsDataStreaming = true
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(FEATURE_CHECK_INTERVAL_MILLIS))
    checkpointer.report()

    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()

    then: "points are now reported"
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0))  {
      groups.size() == 1

      with (groups.iterator().next())  {
        type == "testType"
        group == "testGroup"
        topic == "testTopic"
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

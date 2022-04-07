package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.common.metrics.Sink
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Requires
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.DEFAULT_BUCKET_DURATION_MILLIS
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({
  jvm.isJava8Compatible()
})
class DefaultDataStreamsCheckpointerTest extends DDCoreSpecification {
  def "No payloads written if data streams not supported"() {
    given:
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> false
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)
    def sink = Mock(Sink)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.accept(new StatsPoint("test", "test", "test", 0, 0, timeSource.currentTimeMillis, 0, 0))

    then:
    0 * _ // None of the mocks should have any calls
    checkpointer.inbox.isEmpty() // Internal implementation detail, but no other way to check

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
    def payloadWriter = new CapturingPayloadWriter(sink)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()

    then:
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

  // This test relies on automatic reporting instead of manually calling report
  def "SLOW Write group after a delay"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter(sink)
    def bucketDuration = 200

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, bucketDuration)
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(bucketDuration))

    then:
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

  def "Groups for current bucket are not reported"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter(sink)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS - 100l))
    checkpointer.report()
    // intentional double report. Without the double report, there's a time when the queue is empty and the report hasn't been processed
    checkpointer.report()

    then:
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

  def "All groups written in close"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter(sink)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS - 100l))
    checkpointer.close()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
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
    def payloadWriter = new CapturingPayloadWriter(sink)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()
    // intentional double report. Without the double report, there's a time when the queue is empty and the report hasn't been processed
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
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
    def payloadWriter = new CapturingPayloadWriter(sink)

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(sink, features, timeSource, payloadWriter, DEFAULT_BUCKET_DURATION_MILLIS)
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, 0, 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS - 100l))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, SECONDS.toNanos(10), SECONDS.toNanos(10)))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeMillis, SECONDS.toNanos(5), SECONDS.toNanos(5)))
    checkpointer.accept(new StatsPoint("testType", "testGroup", "testTopic2", 3, 4, timeSource.currentTimeMillis, SECONDS.toNanos(2), 0))
    timeSource.advance(TimeUnit.MILLISECONDS.toNanos(DEFAULT_BUCKET_DURATION_MILLIS))
    checkpointer.report()
    // intentional double report. Without the double report, there's a time when the queue is empty and the report hasn't been processed
    checkpointer.report()

    then:
    conditions.eventually {
      assert checkpointer.inbox.isEmpty()
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

}

class CapturingPayloadWriter implements DatastreamsPayloadWriter {
  boolean accepting = true
  List<StatsBucket> buckets = new ArrayList<>()

  CapturingPayloadWriter(Sink sink) {
    super(sink, "testenv")
  }

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

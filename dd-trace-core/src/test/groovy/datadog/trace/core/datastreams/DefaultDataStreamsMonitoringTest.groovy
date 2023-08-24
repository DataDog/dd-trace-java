package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.TraceConfig
import datadog.trace.api.WellKnownTags
import datadog.trace.api.experimental.DataStreamsContextCarrier
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.Sink
import datadog.trace.core.test.DDCoreSpecification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static DefaultDataStreamsMonitoring.DEFAULT_BUCKET_DURATION_NANOS
import static DefaultDataStreamsMonitoring.FEATURE_CHECK_INTERVAL_NANOS
import static java.util.concurrent.TimeUnit.SECONDS

class DefaultDataStreamsMonitoringTest extends DDCoreSpecification {
  def wellKnownTags = new WellKnownTags("runtimeid", "hostname", "testing", "service", "version", "java")

  def "No payloads written if data streams not supported or not enabled"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> enabledAtAgent
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = Mock(DatastreamsPayloadWriter)
    def sink = Mock(Sink)

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> enabledInConfig
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 0, 0, timeSource.currentTimeNanos, 0, 0))
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }
    0 * payloadWriter.writePayload(_)

    cleanup:
    dataStreams.close()

    where:
    enabledAtAgent | enabledInConfig
    false          | true
    true           | false
    false          | false
  }

  def "Context carrier adapter test"() {
    given:
    def carrier = new CustomContextCarrier()
    def keyName = "keyName"
    def keyValue = "keyValue"
    def extracted = ""

    when:
    DataStreamsContextCarrierAdapter.INSTANCE.set(carrier, keyName, keyValue)
    DataStreamsContextCarrierAdapter.INSTANCE.forEachKey(carrier, new AgentPropagation.KeyClassifier() {
        @Override
        boolean accept(String key, String value) {
          if (key == keyName) {
            extracted = value
            return true
          }
        }
      })
    then:
    extracted == keyValue
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

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
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
    dataStreams.close()
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

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, bucketDuration)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(bucketDuration)

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
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
    dataStreams.close()
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

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 3, 4, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
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
    dataStreams.close()
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

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    dataStreams.close()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
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

  def "Kafka offsets are tracked"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.trackBacklog(new LinkedHashMap<>(["consumer_group":"testGroup", "partition":"2", "topic":"testTopic", "type":"kafka_commit"]), 23)
    dataStreams.trackBacklog(new LinkedHashMap<>(["consumer_group":"testGroup", "partition":"2", "topic":"testTopic", "type":"kafka_commit"]), 24)
    dataStreams.trackBacklog(new LinkedHashMap<>(["partition":"2", "topic":"testTopic", "type":"kafka_produce"]), 23)
    dataStreams.trackBacklog(new LinkedHashMap<>(["partition":"2", "topic":"testTopic2", "type":"kafka_produce"]), 23)
    dataStreams.trackBacklog(new LinkedHashMap<>(["partition":"2", "topic":"testTopic", "type":"kafka_produce"]), 45)
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      backlogs.size() == 3
      List<Map.Entry<List<String>, Long>> sortedBacklogs = new ArrayList<>(backlogs)
      sortedBacklogs.sort({ it.key.toString() })
      with(sortedBacklogs[0]) {
        it.key == ["consumer_group:testGroup", "partition:2", "topic:testTopic", "type:kafka_commit"]
        it.value == 24
      }
      with(sortedBacklogs[1]) {
        it.key == ["partition:2", "topic:testTopic", "type:kafka_produce"]
        it.value == 45
      }
      with(sortedBacklogs[2]) {
        it.key == ["partition:2", "topic:testTopic2", "type:kafka_produce"]
        it.value == 23
      }
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
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

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
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
    dataStreams.close()
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

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(10), SECONDS.toNanos(10)))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, SECONDS.toNanos(5), SECONDS.toNanos(5)))
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic2"], 3, 4, timeSource.currentTimeNanos, SECONDS.toNanos(2), 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
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
    dataStreams.close()
  }

  def "feature upgrade"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    boolean supportsDataStreaming = false
    def features = Mock(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> { return supportsDataStreaming }
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when: "reporting points when data streams is not supported"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "report called multiple times without advancing past check interval"
    dataStreams.report()
    dataStreams.report()
    dataStreams.report()

    then: "features are not rechecked"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }
    0 * features.discover()
    payloadWriter.buckets.isEmpty()

    when: "submitting points after an upgrade"
    supportsDataStreaming = true
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS)
    dataStreams.report()

    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "points are now reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
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
    dataStreams.close()
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

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when: "reporting points after a downgrade"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    supportsDataStreaming = false
    dataStreams.onEvent(EventListener.EventType.DOWNGRADED, "")
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "submitting points after an upgrade"
    supportsDataStreaming = true
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS)
    dataStreams.report()

    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "points are now reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
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
    dataStreams.close()
  }

  def "dynamic config enable and disable"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    boolean supportsDataStreaming = true
    def features = Mock(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> { return supportsDataStreaming }
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    boolean dsmEnabled = false
    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> { return dsmEnabled }
    }

    when: "reporting points when data streams is not enabled"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "submitting points after dynamically enabled"
    dsmEnabled = true
    dataStreams.report()

    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "points are now reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
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

    when: "disabling data streams dynamically"
    dsmEnabled = false
    dataStreams.report()

    then: "inbox is processed"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
    }

    when: "submitting points after being disabled"
    payloadWriter.buckets.clear()

    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "points are no longer reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert payloadWriter.buckets.isEmpty()
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "feature and dynamic config upgrade interactions"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    boolean supportsDataStreaming = false
    def features = Mock(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> { return supportsDataStreaming }
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    boolean dsmEnabled = false
    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> { return dsmEnabled }
    }

    when: "reporting points when data streams is not supported"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "submitting points after an upgrade with dsm disabled"
    supportsDataStreaming = true
    timeSource.advance(FEATURE_CHECK_INTERVAL_NANOS)
    dataStreams.report()

    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "points are not reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert payloadWriter.buckets.isEmpty()
    }

    when: "dsm is enabled dynamically"
    dsmEnabled = true
    dataStreams.report()

    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "points are now reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
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
    dataStreams.close()
  }

  def "more feature and dynamic config upgrade interactions"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    boolean supportsDataStreaming = false
    def features = Mock(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> { return supportsDataStreaming }
    }
    def timeSource = new ControllableTimeSource()
    def sink = Mock(Sink)
    def payloadWriter = new CapturingPayloadWriter()

    boolean dsmEnabled = false
    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> { return dsmEnabled }
    }

    when: "reporting points when data streams is not supported"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, wellKnownTags, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "no buckets are reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }

    payloadWriter.buckets.isEmpty()

    when: "enabling dsm when not supported by agent"
    dsmEnabled = true
    dataStreams.report()

    dataStreams.add(new StatsPoint(["type:testType", "group:testGroup", "topic:testTopic"], 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "points are not reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert payloadWriter.buckets.isEmpty()
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
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

class CustomContextCarrier implements DataStreamsContextCarrier {

  private Map<String, Object> data = new HashMap<>()

  @Override
  Set<Map.Entry<String, Object>> entries() {
    return data.entrySet()
  }

  @Override
  void set(String key, String value) {
    data.put(key, value)
  }
}

package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.Config
import datadog.trace.api.TraceConfig
import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.datastreams.StatsPoint
import datadog.trace.api.experimental.DataStreamsContextCarrier
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.Sink
import datadog.trace.core.test.DDCoreSpecification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

import static DefaultDataStreamsMonitoring.FEATURE_CHECK_INTERVAL_NANOS
import static java.util.concurrent.TimeUnit.SECONDS

class DefaultDataStreamsMonitoringTest extends DDCoreSpecification {
  static final DEFAULT_BUCKET_DURATION_NANOS = Config.get().getDataStreamsBucketDurationNanoseconds()

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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", null, "testTopic", "testGroup", null), 0, 0, 0, timeSource.currentTimeNanos, 0, 0, 0, null))
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

  def "Schema sampler samples with correct weights"() {
    given:
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    timeSource.set(1e12 as long)
    def payloadWriter = Mock(DatastreamsPayloadWriter)
    def sink = Mock(Sink)
    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)

    then:
    // the first received schema is sampled, with a weight of one.
    dataStreams.canSampleSchema("schema1")
    dataStreams.trySampleSchema("schema1") == 1
    // the sampling is done by topic, so a schema on a different topic will also be sampled at once, also with a weight of one.
    dataStreams.canSampleSchema("schema2")
    dataStreams.trySampleSchema("schema2") == 1
    // no time has passed from the last sampling, so the same schema is not sampled again (two times in a row).
    !dataStreams.canSampleSchema("schema1")
    !dataStreams.canSampleSchema("schema1")
    timeSource.advance(30*1e9 as long)
    // now, 30 seconds have passed, so the schema is sampled again, with a weight of 3 (so it includes the two times the schema was not sampled).
    dataStreams.canSampleSchema("schema1")
    dataStreams.trySampleSchema("schema1") == 3
  }

  def "Context carrier adapter test"() {
    given:
    def carrier = new CustomContextCarrier()
    def keyName = "keyName"
    def keyValue = "keyValue"
    def extracted = ""

    when:
    DataStreamsContextCarrierAdapter.INSTANCE.set(carrier, keyName, keyValue)
    DataStreamsContextCarrierAdapter.INSTANCE.forEachKeyValue(carrier, new BiConsumer<String, String>() {
        @Override
        void accept(String key, String value) {
          if (key == keyName) {
            extracted = value
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, bucketDuration)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(tg, 3, 4, 3,  timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    def tg2 = DataStreamsTags.create("testType", null, "testTopic2", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 5, timeSource.currentTimeNanos, 0, 0, 0, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(tg2, 3, 4, 6, timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
        hash == 1
        parentHash == 2
      }
    }

    with(payloadWriter.buckets.get(1)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic2"
        tags.nonNullSize() == 3
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_commit", "testTopic", "2", null, "testGroup"), 23)
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_commit", "testTopic", "2", null, "testGroup"), 24)
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "2", null, null), 23)
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic2", "2", null, null), 23)
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "2", null, null), 45)
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
      def list = backlogs.sort({ it.key.toString() })
      with(list[0]) {
        it.key == DataStreamsTags.createWithPartition("kafka_commit", "testTopic", "2", null, "testGroup")
        it.value == 24
      }
      with(list[1]) {
        it.key == DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "2", null, null)
        it.value == 45
      }
      with(list[2]) {
        it.key == DataStreamsTags.createWithPartition("kafka_produce", "testTopic2", "2", null, null)
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 5, timeSource.currentTimeNanos, 0, 0, 0, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS*10)
    def tg2 = DataStreamsTags.create("testType", null, "testTopic2", "testGroup", null)
    dataStreams.add(new StatsPoint(tg2, 3, 4, 6, timeSource.currentTimeNanos, 0, 0, 0, null))
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
      groups
      with(groups.iterator().next()) {
        tags.nonNullSize() == 3
        tags.getType() == "type:testType"
        tags.getGroup() == "group:testGroup"
        tags.getTopic() == "topic:testTopic"
        hash == 1
        parentHash == 2
      }
    }

    with(payloadWriter.buckets.get(1)) {
      groups.size() == 1

      with(groups.iterator().next()) {
        tags.getType() == "type:testType"
        tags.getGroup() == "group:testGroup"
        tags.getTopic() == "topic:testTopic2"
        tags.nonNullSize() == 3
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
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(tg, 1, 2, 1, timeSource.currentTimeNanos, 0, 0, 0, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    dataStreams.add(new StatsPoint(tg, 1, 2, 1, timeSource.currentTimeNanos, SECONDS.toNanos(10), SECONDS.toNanos(10), 10, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(tg, 1, 2,1, timeSource.currentTimeNanos, SECONDS.toNanos(5), SECONDS.toNanos(5), 5, null))
    dataStreams.add(new StatsPoint(tg, 3, 4, 5, timeSource.currentTimeNanos, SECONDS.toNanos(2), 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
        hash == 1
        parentHash == 2
        Math.abs((pathwayLatency.getMaxValue()-10)/10) < 0.01
      }
    }

    with(payloadWriter.buckets.get(1)) {
      groups.size() == 2

      List<StatsGroup> sortedGroups = new ArrayList<>(groups)
      sortedGroups.sort({ it.hash })

      with(sortedGroups[0]) {
        hash == 1
        parentHash == 2
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
        Math.abs((pathwayLatency.getMaxValue()-5)/5) < 0.01
      }

      with(sortedGroups[1]) {
        hash == 3
        parentHash == 4
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
        Math.abs((pathwayLatency.getMaxValue()-2)/2) < 0.01
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    supportsDataStreaming = false
    dataStreams.onEvent(EventListener.EventType.DOWNGRADED, "")
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
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
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
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

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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
        tags.type == "type:testType"
        tags.group == "group:testGroup"
        tags.topic == "topic:testTopic"
        tags.nonNullSize() == 3
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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

    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
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

  void writePayload(Collection<StatsBucket> payload, String serviceNameOverride) {
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

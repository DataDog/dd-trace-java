package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.Config
import datadog.trace.api.TraceConfig
import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.datastreams.KafkaConfigReport
import datadog.trace.api.datastreams.SchemaRegistryUsage
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

  def "Schema registry usages are aggregated by operation"() {
    given:
    def conditions = new PollingConditions(timeout: 2)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = new CapturingPayloadWriter()
    def sink = Mock(Sink)
    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()

    // Record serialize and deserialize operations
    dataStreams.reportSchemaRegistryUsage("test-topic", "test-cluster", 123, true, false, "serialize")
    dataStreams.reportSchemaRegistryUsage("test-topic", "test-cluster", 123, true, false, "serialize") // duplicate serialize
    dataStreams.reportSchemaRegistryUsage("test-topic", "test-cluster", 123, true, false, "deserialize")
    dataStreams.reportSchemaRegistryUsage("test-topic", "test-cluster", 456, true, true, "serialize") // different schema/key

    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      schemaRegistryUsages.size() == 3 // 3 unique combinations

      // Find serialize operation for schema 123 (should have count 2)
      def serializeUsage = schemaRegistryUsages.find { e ->
        e.key.schemaId == 123 && e.key.operation == "serialize" && !e.key.isKey
      }
      serializeUsage != null
      serializeUsage.value == 2L  // Aggregated 2 serialize operations

      // Find deserialize operation for schema 123 (should have count 1)
      def deserializeUsage = schemaRegistryUsages.find { e ->
        e.key.schemaId == 123 && e.key.operation == "deserialize" && !e.key.isKey
      }
      deserializeUsage != null
      deserializeUsage.value == 1L

      // Find serialize operation for schema 456 with isKey=true (should have count 1)
      def keySerializeUsage = schemaRegistryUsages.find { e ->
        e.key.schemaId == 456 && e.key.operation == "serialize" && e.key.isKey
      }
      keySerializeUsage != null
      keySerializeUsage.value == 1L
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "SchemaKey equals and hashCode work correctly"() {
    given:
    def key1 = new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize")
    def key2 = new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize")
    def key3 = new StatsBucket.SchemaKey("topic2", "cluster1", 123, true, false, "serialize") // different topic
    def key4 = new StatsBucket.SchemaKey("topic1", "cluster2", 123, true, false, "serialize") // different cluster
    def key5 = new StatsBucket.SchemaKey("topic1", "cluster1", 456, true, false, "serialize") // different schema
    def key6 = new StatsBucket.SchemaKey("topic1", "cluster1", 123, false, false, "serialize") // different success
    def key7 = new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, true, "serialize") // different isKey
    def key8 = new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "deserialize") // different operation

    expect:
    // Reflexive
    key1.equals(key1)
    key1.hashCode() == key1.hashCode()

    // Symmetric
    key1.equals(key2)
    key2.equals(key1)
    key1.hashCode() == key2.hashCode()

    // Different topic
    !key1.equals(key3)
    !key3.equals(key1)

    // Different cluster
    !key1.equals(key4)
    !key4.equals(key1)

    // Different schema ID
    !key1.equals(key5)
    !key5.equals(key1)

    // Different success
    !key1.equals(key6)
    !key6.equals(key1)

    // Different isKey
    !key1.equals(key7)
    !key7.equals(key1)

    // Different operation
    !key1.equals(key8)
    !key8.equals(key1)

    // Null check
    !key1.equals(null)

    // Different class
    !key1.equals("not a schema key")
  }

  def "StatsBucket aggregates schema registry usages correctly"() {
    given:
    def bucket = new StatsBucket(1000L, 10000L)
    def usage1 = new SchemaRegistryUsage("topic1", "cluster1", 123, true, false, "serialize", 1000L, null)
    def usage2 = new SchemaRegistryUsage("topic1", "cluster1", 123, true, false, "serialize", 2000L, null)
    def usage3 = new SchemaRegistryUsage("topic1", "cluster1", 123, true, false, "deserialize", 3000L, null)

    when:
    bucket.addSchemaRegistryUsage(usage1)
    bucket.addSchemaRegistryUsage(usage2) // should increment count for same key
    bucket.addSchemaRegistryUsage(usage3) // different operation, new key

    def usages = bucket.getSchemaRegistryUsages()
    def usageMap = usages.collectEntries { [(it.key): it.value] }

    then:
    usages.size() == 2

    // Check serialize count
    def serializeKey = new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "serialize")
    usageMap[serializeKey] == 2L

    // Check deserialize count
    def deserializeKey = new StatsBucket.SchemaKey("topic1", "cluster1", 123, true, false, "deserialize")
    usageMap[deserializeKey] == 1L

    // Check that different operations create different keys
    serializeKey != deserializeKey
  }

  def "Kafka producer config is reported in bucket"() {
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
    dataStreams.reportKafkaConfig("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"])
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 1
      with(kafkaConfigs.get(0)) {
        type == "kafka_producer"
        config["bootstrap.servers"] == "localhost:9092"
        config["acks"] == "all"
      }
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "Kafka consumer config is reported in bucket"() {
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
    dataStreams.reportKafkaConfig("kafka_consumer", "", "test-group", ["bootstrap.servers": "localhost:9092", "group.id": "test-group", "auto.offset.reset": "earliest"])
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 1
      with(kafkaConfigs.get(0)) {
        type == "kafka_consumer"
        config["bootstrap.servers"] == "localhost:9092"
        config["group.id"] == "test-group"
        config["auto.offset.reset"] == "earliest"
      }
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "Duplicate Kafka configs are deduplicated and only sent once"() {
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

    when: "reporting the same config twice"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def config1 = ["bootstrap.servers": "localhost:9092", "acks": "all"]
    def config2 = ["bootstrap.servers": "localhost:9092", "acks": "all"]
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config1)
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config2) // duplicate, should be ignored
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "only one config is reported in the bucket"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 1
      with(kafkaConfigs.get(0)) {
        type == "kafka_producer"
        config["bootstrap.servers"] == "localhost:9092"
        config["acks"] == "all"
      }
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "Duplicate Kafka configs across buckets are deduplicated"() {
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

    when: "reporting the same config in two different bucket windows"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def config = ["bootstrap.servers": "localhost:9092", "acks": "all"]
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config)
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "first bucket has the config"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 1
    }

    when: "reporting the same config again in a new bucket"
    payloadWriter.buckets.clear()
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config) // duplicate, should be ignored globally
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "second bucket has no configs because the duplicate was filtered"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }
    // Either no buckets at all, or buckets with empty kafkaConfigs
    payloadWriter.buckets.every { it.kafkaConfigs.isEmpty() }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "Different Kafka configs are both reported"() {
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

    when: "reporting producer and consumer configs"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.reportKafkaConfig("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"])
    dataStreams.reportKafkaConfig("kafka_consumer", "", "my-group", ["bootstrap.servers": "localhost:9092", "group.id": "my-group"])
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "both configs are reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 2

      def producerConfig = kafkaConfigs.find { it.type == "kafka_producer" }
      producerConfig != null
      producerConfig.config["bootstrap.servers"] == "localhost:9092"
      producerConfig.config["acks"] == "all"

      def consumerConfig = kafkaConfigs.find { it.type == "kafka_consumer" }
      consumerConfig != null
      consumerConfig.config["bootstrap.servers"] == "localhost:9092"
      consumerConfig.config["group.id"] == "my-group"
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "Kafka configs with different values for same type are not deduplicated"() {
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

    when: "reporting two producer configs with different settings"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.reportKafkaConfig("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"])
    dataStreams.reportKafkaConfig("kafka_producer", "", "", ["bootstrap.servers": "localhost:9093", "acks": "1"])
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "both configs are reported because they have different values"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 2
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "Kafka configs are reported alongside stats points"() {
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

    when: "reporting both stats points and kafka configs"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 3, timeSource.currentTimeNanos, 0, 0, 0, null))
    dataStreams.reportKafkaConfig("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092"])
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "bucket contains both stats groups and kafka configs"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      groups.size() == 1
      kafkaConfigs.size() == 1

      with(groups.iterator().next()) {
        tags.type == "type:testType"
        hash == 1
        parentHash == 2
      }

      with(kafkaConfigs.get(0)) {
        type == "kafka_producer"
        config["bootstrap.servers"] == "localhost:9092"
      }
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "Kafka configs not reported when DSM is disabled"() {
    given:
    def conditions = new PollingConditions(timeout: 1)
    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> enabledAtAgent
    }
    def timeSource = new ControllableTimeSource()
    def payloadWriter = new CapturingPayloadWriter()
    def sink = Mock(Sink)

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> enabledInConfig
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    dataStreams.reportKafkaConfig("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092"])
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
    }
    payloadWriter.buckets.isEmpty()

    cleanup:
    payloadWriter.close()
    dataStreams.close()

    where:
    enabledAtAgent | enabledInConfig
    false          | true
    true           | false
    false          | false
  }

  def "Kafka configs flushed on close"() {
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
    dataStreams.reportKafkaConfig("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092"])
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    dataStreams.close()

    then: "configs in the current bucket are flushed on close"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 1
      with(kafkaConfigs.get(0)) {
        type == "kafka_producer"
        config["bootstrap.servers"] == "localhost:9092"
      }
    }

    cleanup:
    payloadWriter.close()
  }

  def "clear() resets Kafka config dedup cache"() {
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

    when: "reporting config, flushing, clearing, then reporting the same config again"
    def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig }, payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.start()
    def config = ["bootstrap.servers": "localhost:9092", "acks": "all"]
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config)
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "first config is reported"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 1
    }

    when: "clearing the state and reporting the same config"
    payloadWriter.buckets.clear()
    dataStreams.clear()
    dataStreams.reportKafkaConfig("kafka_producer", "", "", config)
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then: "the config is reported again because the dedup cache was cleared"
    conditions.eventually {
      assert dataStreams.inbox.isEmpty()
      assert dataStreams.thread.state != Thread.State.RUNNABLE
      assert payloadWriter.buckets.size() == 1
    }

    with(payloadWriter.buckets.get(0)) {
      kafkaConfigs.size() == 1
      with(kafkaConfigs.get(0)) {
        type == "kafka_producer"
        config["bootstrap.servers"] == "localhost:9092"
      }
    }

    cleanup:
    payloadWriter.close()
    dataStreams.close()
  }

  def "KafkaConfigReport equals and hashCode work correctly"() {
    given:
    def config1 = new KafkaConfigReport("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"], 1000L, null)
    def config2 = new KafkaConfigReport("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"], 2000L, null)
    def config3 = new KafkaConfigReport("kafka_consumer", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"], 1000L, null)
    def config4 = new KafkaConfigReport("kafka_producer", "", "", ["bootstrap.servers": "localhost:9093"], 1000L, null)
    def config5 = new KafkaConfigReport("kafka_producer", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"], 1000L, "other-service")

    expect:
    // Reflexive
    config1.equals(config1)
    config1.hashCode() == config1.hashCode()

    // Same type and config, different timestamp -- equals (timestamp is NOT part of equals)
    config1.equals(config2)
    config2.equals(config1)
    config1.hashCode() == config2.hashCode()

    // Same type and config, different serviceNameOverride -- equals (serviceNameOverride is NOT part of equals)
    config1.equals(config5)
    config5.equals(config1)
    config1.hashCode() == config5.hashCode()

    // Different type
    !config1.equals(config3)
    !config3.equals(config1)

    // Different config values
    !config1.equals(config4)
    !config4.equals(config1)

    // Null check
    !config1.equals(null)

    // Different class
    !config1.equals("not a config report")
  }

  def "StatsBucket stores Kafka configs"() {
    given:
    def bucket = new StatsBucket(1000L, 10000L)
    def config1 = new KafkaConfigReport("kafka_producer", "", "", ["acks": "all"], 1000L, null)
    def config2 = new KafkaConfigReport("kafka_consumer", "", "test", ["group.id": "test"], 2000L, null)

    when:
    bucket.addKafkaConfig(config1)
    bucket.addKafkaConfig(config2)

    then:
    bucket.getKafkaConfigs().size() == 2
    bucket.getKafkaConfigs().get(0).type == "kafka_producer"
    bucket.getKafkaConfigs().get(0).config["acks"] == "all"
    bucket.getKafkaConfigs().get(1).type == "kafka_consumer"
    bucket.getKafkaConfigs().get(1).config["group.id"] == "test"
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

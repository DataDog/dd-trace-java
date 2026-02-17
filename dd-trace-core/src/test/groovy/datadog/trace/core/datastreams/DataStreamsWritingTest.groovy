package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.http.OkHttpUtils
import datadog.trace.api.Config
import datadog.trace.api.ProcessTags
import datadog.trace.api.TraceConfig
import datadog.trace.api.WellKnownTags
import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.api.datastreams.StatsPoint
import datadog.trace.core.DDTraceCoreInfo
import datadog.trace.core.test.DDCoreSpecification
import okhttp3.HttpUrl
import okio.BufferedSource
import okio.GzipSource
import okio.Okio
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED
import static java.util.concurrent.TimeUnit.SECONDS

/**
 * This test class exists because a real integration test is not possible. see DataStreamsIntegrationTest
 */
class DataStreamsWritingTest extends DDCoreSpecification {
  @Shared
  List<byte[]> requestBodies

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      post(DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT) {
        owner.owner.owner.requestBodies.add(request.body)
        response.status(200).send()
      }
    }
  }

  static final DEFAULT_BUCKET_DURATION_NANOS = Config.get().getDataStreamsBucketDurationNanoseconds()
  def setup() {
    requestBodies = []
  }

  def "Service overrides split buckets"() {
    given:
    def conditions = new PollingConditions(timeout: 2)

    def testOkhttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)

    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }

    def wellKnownTags = new WellKnownTags("runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java")

    def fakeConfig = Stub(Config) {
      getAgentUrl() >> server.address.toString()
      getWellKnownTags() >> wellKnownTags
      getPrimaryTag() >> "region-1"
    }

    def sharedCommObjects = new SharedCommunicationObjects()
    sharedCommObjects.featuresDiscovery = features
    sharedCommObjects.agentHttpClient = testOkhttpClient
    sharedCommObjects.createRemaining(fakeConfig)

    def timeSource = new ControllableTimeSource()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }
    def serviceNameOverride = "service-name-override"

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(fakeConfig, sharedCommObjects, timeSource, { traceConfig })
    dataStreams.start()
    dataStreams.setThreadServiceName(serviceNameOverride)
    dataStreams.add(new StatsPoint(DataStreamsTags.create(null, null), 9, 0, 10, timeSource.currentTimeNanos, 0, 0, 0, serviceNameOverride))
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 130)
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    // force flush
    dataStreams.report()
    dataStreams.close()
    dataStreams.clearThreadServiceName()
    then:
    conditions.eventually {
      assert requestBodies.size() == 1
    }
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(requestBodies[0])))

    BufferedSource bufferedSource = Okio.buffer(gzipSource)
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream())

    assert unpacker.unpackMapHeader() == 9
    assert unpacker.unpackString() == "Env"
    assert unpacker.unpackString() == "test"
    assert unpacker.unpackString() == "Service"
    assert unpacker.unpackString() == serviceNameOverride
  }

  def "Write bucket to mock server with process tags enabled #processTagsEnabled"() {
    setup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "$processTagsEnabled")
    ProcessTags.reset()

    def conditions = new PollingConditions(timeout: 2)

    def testOkhttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)

    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }

    def wellKnownTags = new WellKnownTags("runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java")

    def fakeConfig = Stub(Config) {
      getAgentUrl() >> server.address.toString()
      getWellKnownTags() >> wellKnownTags
      getPrimaryTag() >> "region-1"
    }

    def sharedCommObjects = new SharedCommunicationObjects()
    sharedCommObjects.featuresDiscovery = features
    sharedCommObjects.agentHttpClient = testOkhttpClient
    sharedCommObjects.createRemaining(fakeConfig)

    def timeSource = new ControllableTimeSource()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(fakeConfig, sharedCommObjects, timeSource, { traceConfig })
    dataStreams.start()
    dataStreams.add(new StatsPoint(DataStreamsTags.create(null, null), 9, 0, 10, timeSource.currentTimeNanos, 0, 0, 0, null))
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.INBOUND, "testTopic", "testGroup", null), 1, 2, 5, timeSource.currentTimeNanos, 0, 0, 0, null))
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 100)
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 130)
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.INBOUND, "testTopic", "testGroup", null), 1, 2, 5, timeSource.currentTimeNanos, SECONDS.toNanos(10), SECONDS.toNanos(10), 10, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.INBOUND, "testTopic", "testGroup", null), 1, 2, 5, timeSource.currentTimeNanos, SECONDS.toNanos(5), SECONDS.toNanos(5), 5, null))
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.INBOUND, "testTopic2", "testGroup", null), 3, 4, 6, timeSource.currentTimeNanos, SECONDS.toNanos(2), 0, 2, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.close()

    then:
    conditions.eventually {
      assert requestBodies.size() == 1
    }

    validateMessage(requestBodies[0], processTagsEnabled)

    cleanup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
    ProcessTags.reset()

    where:
    processTagsEnabled << [true, false]
  }

  def "Write Kafka configs to mock server"() {
    given:
    def conditions = new PollingConditions(timeout: 2)

    def testOkhttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)

    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }

    def wellKnownTags = new WellKnownTags("runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java")

    def fakeConfig = Stub(Config) {
      getAgentUrl() >> server.address.toString()
      getWellKnownTags() >> wellKnownTags
      getPrimaryTag() >> "region-1"
    }

    def sharedCommObjects = new SharedCommunicationObjects()
    sharedCommObjects.featuresDiscovery = features
    sharedCommObjects.agentHttpClient = testOkhttpClient
    sharedCommObjects.createRemaining(fakeConfig)

    def timeSource = new ControllableTimeSource()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(fakeConfig, sharedCommObjects, timeSource, { traceConfig })
    dataStreams.start()

    // Report a producer and consumer config
    dataStreams.reportKafkaConfig("kafka_producer", "", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all", "linger.ms": "5"])
    dataStreams.reportKafkaConfig("kafka_consumer", "", "", "test-group", ["bootstrap.servers": "localhost:9092", "group.id": "test-group", "auto.offset.reset": "earliest"])

    // Also add a stats point so the bucket is not empty of stats
    dataStreams.add(new StatsPoint(DataStreamsTags.create(null, null), 9, 0, 10, timeSource.currentTimeNanos, 0, 0, 0, null))

    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.close()

    then:
    conditions.eventually {
      assert requestBodies.size() == 1
    }

    validateKafkaConfigMessage(requestBodies[0])
  }

  def "Duplicate Kafka configs are not serialized twice"() {
    given:
    def conditions = new PollingConditions(timeout: 2)

    def testOkhttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)

    def features = Stub(DDAgentFeaturesDiscovery) {
      supportsDataStreams() >> true
    }

    def wellKnownTags = new WellKnownTags("runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java")

    def fakeConfig = Stub(Config) {
      getAgentUrl() >> server.address.toString()
      getWellKnownTags() >> wellKnownTags
      getPrimaryTag() >> "region-1"
    }

    def sharedCommObjects = new SharedCommunicationObjects()
    sharedCommObjects.featuresDiscovery = features
    sharedCommObjects.agentHttpClient = testOkhttpClient
    sharedCommObjects.createRemaining(fakeConfig)

    def timeSource = new ControllableTimeSource()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(fakeConfig, sharedCommObjects, timeSource, { traceConfig })
    dataStreams.start()

    // Report the same producer config twice
    dataStreams.reportKafkaConfig("kafka_producer", "", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"])
    dataStreams.reportKafkaConfig("kafka_producer", "", "", "", ["bootstrap.servers": "localhost:9092", "acks": "all"])

    // Also add a stats point so the bucket has content
    dataStreams.add(new StatsPoint(DataStreamsTags.create(null, null), 9, 0, 10, timeSource.currentTimeNanos, 0, 0, 0, null))

    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.close()

    then:
    conditions.eventually {
      assert requestBodies.size() == 1
    }

    validateDedupedKafkaConfigMessage(requestBodies[0])
  }

  def validateKafkaConfigMessage(byte[] message) {
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(message)))
    BufferedSource bufferedSource = Okio.buffer(gzipSource)
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream())

    // Outer map (same structure as other payloads)
    def outerMapSize = unpacker.unpackMapHeader()
    // Skip to Stats array
    boolean foundStats = false
    for (int i = 0; i < outerMapSize; i++) {
      def key = unpacker.unpackString()
      if (key == "Stats") {
        foundStats = true
        def numBuckets = unpacker.unpackArrayHeader()
        assert numBuckets >= 1

        // Parse first bucket
        def bucketMapSize = unpacker.unpackMapHeader()
        boolean foundConfigs = false
        for (int j = 0; j < bucketMapSize; j++) {
          def bucketKey = unpacker.unpackString()
          if (bucketKey == "Configs") {
            foundConfigs = true
            def numConfigs = unpacker.unpackArrayHeader()
            assert numConfigs == 2

            // Collect configs in a map keyed by type
            Map<String, Map<String, String>> configsByType = [:]
            numConfigs.times {
              assert unpacker.unpackMapHeader() == 2
              assert unpacker.unpackString() == "Type"
              def type = unpacker.unpackString()
              assert unpacker.unpackString() == "Config"
              def configSize = unpacker.unpackMapHeader()
              Map<String, String> configEntries = [:]
              configSize.times {
                def ck = unpacker.unpackString()
                def cv = unpacker.unpackString()
                configEntries[ck] = cv
              }
              configsByType[type] = configEntries
            }

            // Verify producer config
            assert configsByType.containsKey("kafka_producer")
            assert configsByType["kafka_producer"]["bootstrap.servers"] == "localhost:9092"
            assert configsByType["kafka_producer"]["acks"] == "all"
            assert configsByType["kafka_producer"]["linger.ms"] == "5"

            // Verify consumer config
            assert configsByType.containsKey("kafka_consumer")
            assert configsByType["kafka_consumer"]["bootstrap.servers"] == "localhost:9092"
            assert configsByType["kafka_consumer"]["group.id"] == "test-group"
            assert configsByType["kafka_consumer"]["auto.offset.reset"] == "earliest"
          } else {
            unpacker.skipValue()
          }
        }
        assert foundConfigs : "Configs field not found in bucket"

        // Skip remaining buckets
        for (int b = 1; b < numBuckets; b++) {
          unpacker.skipValue()
        }
      } else {
        unpacker.skipValue()
      }
    }
    assert foundStats : "Stats field not found in payload"
    return true
  }

  def validateDedupedKafkaConfigMessage(byte[] message) {
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(message)))
    BufferedSource bufferedSource = Okio.buffer(gzipSource)
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream())

    def outerMapSize = unpacker.unpackMapHeader()
    boolean foundStats = false
    for (int i = 0; i < outerMapSize; i++) {
      def key = unpacker.unpackString()
      if (key == "Stats") {
        foundStats = true
        def numBuckets = unpacker.unpackArrayHeader()
        assert numBuckets >= 1

        // Parse first bucket
        def bucketMapSize = unpacker.unpackMapHeader()
        boolean foundConfigs = false
        for (int j = 0; j < bucketMapSize; j++) {
          def bucketKey = unpacker.unpackString()
          if (bucketKey == "Configs") {
            foundConfigs = true
            def numConfigs = unpacker.unpackArrayHeader()
            // Only 1 config should be present (deduplication)
            assert numConfigs == 1

            assert unpacker.unpackMapHeader() == 2
            assert unpacker.unpackString() == "Type"
            assert unpacker.unpackString() == "kafka_producer"
            assert unpacker.unpackString() == "Config"
            def configSize = unpacker.unpackMapHeader()
            Map<String, String> configEntries = [:]
            configSize.times {
              configEntries[unpacker.unpackString()] = unpacker.unpackString()
            }
            assert configEntries["bootstrap.servers"] == "localhost:9092"
            assert configEntries["acks"] == "all"
          } else {
            unpacker.skipValue()
          }
        }
        assert foundConfigs : "Configs field not found in bucket"

        for (int b = 1; b < numBuckets; b++) {
          unpacker.skipValue()
        }
      } else {
        unpacker.skipValue()
      }
    }
    assert foundStats : "Stats field not found in payload"
    return true
  }

  def validateMessage(byte[] message, boolean processTagsEnabled) {
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(message)))

    BufferedSource bufferedSource = Okio.buffer(gzipSource)
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream())

    assert unpacker.unpackMapHeader() == 8 + (processTagsEnabled ? 1 : 0)
    assert unpacker.unpackString() == "Env"
    assert unpacker.unpackString() == "test"
    assert unpacker.unpackString() == "Service"
    assert unpacker.unpackString() == Config.get().getServiceName()
    assert unpacker.unpackString() == "Lang"
    assert unpacker.unpackString() == "java"
    assert unpacker.unpackString() == "PrimaryTag"
    assert unpacker.unpackString() == "region-1"
    assert unpacker.unpackString() == "TracerVersion"
    assert unpacker.unpackString() == DDTraceCoreInfo.VERSION
    assert unpacker.unpackString() == "Version"
    assert unpacker.unpackString() == "version"
    assert unpacker.unpackString() == "Stats"
    assert unpacker.unpackArrayHeader() == 2  // 2 time buckets

    // FIRST BUCKET
    assert unpacker.unpackMapHeader() == 4
    assert unpacker.unpackString() == "Start"
    unpacker.skipValue()
    assert unpacker.unpackString() == "Duration"
    assert unpacker.unpackLong() == DEFAULT_BUCKET_DURATION_NANOS
    assert unpacker.unpackString() == "Stats"
    assert unpacker.unpackArrayHeader() == 2 // 2 groups in first bucket

    Set availableSizes = [5, 6] // we don't know the order the groups will be reported
    2.times {
      int mapHeaderSize = unpacker.unpackMapHeader()
      assert availableSizes.remove(mapHeaderSize)
      if (mapHeaderSize == 5) {
        // empty topic group
        assert unpacker.unpackString() == "PathwayLatency"
        unpacker.skipValue()
        assert unpacker.unpackString() == "EdgeLatency"
        unpacker.skipValue()
        assert unpacker.unpackString() == "PayloadSize"
        unpacker.skipValue()
        assert unpacker.unpackString() == "Hash"
        assert unpacker.unpackLong() == 9
        assert unpacker.unpackString() == "ParentHash"
        assert unpacker.unpackLong() == 0
      } else {
        //other group
        assert unpacker.unpackString() == "PathwayLatency"
        unpacker.skipValue()
        assert unpacker.unpackString() == "EdgeLatency"
        unpacker.skipValue()
        assert unpacker.unpackString() == "PayloadSize"
        unpacker.skipValue()
        assert unpacker.unpackString() == "Hash"
        assert unpacker.unpackLong() == 1
        assert unpacker.unpackString() == "ParentHash"
        assert unpacker.unpackLong() == 2
        assert unpacker.unpackString() == "EdgeTags"
        assert unpacker.unpackArrayHeader() == 4
        assert unpacker.unpackString() == "direction:in"
        assert unpacker.unpackString() == "topic:testTopic"
        assert unpacker.unpackString() == "type:testType"
        assert unpacker.unpackString() == "group:testGroup"
      }
    }

    // Kafka stats
    assert unpacker.unpackString() == "Backlogs"
    assert unpacker.unpackArrayHeader() == 1
    assert unpacker.unpackMapHeader() == 2
    assert unpacker.unpackString() == "Tags"
    assert unpacker.unpackArrayHeader() == 3
    assert unpacker.unpackString() == "topic:testTopic"
    assert unpacker.unpackString() == "type:kafka_produce"
    assert unpacker.unpackString() == "partition:1"
    assert unpacker.unpackString() == "Value"
    assert unpacker.unpackLong() == 130

    // SECOND BUCKET
    assert unpacker.unpackMapHeader() == 3
    assert unpacker.unpackString() == "Start"
    unpacker.skipValue()
    assert unpacker.unpackString() == "Duration"
    assert unpacker.unpackLong() == DEFAULT_BUCKET_DURATION_NANOS
    assert unpacker.unpackString() == "Stats"
    assert unpacker.unpackArrayHeader() == 2 // 2 groups in second bucket

    Set<Long> availableHashes = [1L, 3L] // we don't know the order the groups will be reported
    2.times {
      assert unpacker.unpackMapHeader() == 6
      assert unpacker.unpackString() == "PathwayLatency"
      unpacker.skipValue()
      assert unpacker.unpackString() == "EdgeLatency"
      unpacker.skipValue()
      assert unpacker.unpackString() == "PayloadSize"
      unpacker.skipValue()
      assert unpacker.unpackString() == "Hash"
      def hash = unpacker.unpackLong()
      assert availableHashes.remove(hash)
      assert unpacker.unpackString() == "ParentHash"
      assert unpacker.unpackLong() == (hash == 1 ? 2 : 4)
      assert unpacker.unpackString() == "EdgeTags"
      assert unpacker.unpackArrayHeader() == 4
      assert unpacker.unpackString() == "direction:in"
      assert unpacker.unpackString() == (hash == 1 ? "topic:testTopic" : "topic:testTopic2")
      assert unpacker.unpackString() == "type:testType"
      assert unpacker.unpackString() == "group:testGroup"
    }

    assert unpacker.unpackString() == "ProductMask"
    assert unpacker.unpackLong() == 1

    def processTags = ProcessTags.getTagsAsStringList()
    assert unpacker.hasNext() == (processTags != null)
    if (processTags != null) {
      assert unpacker.unpackString() == "ProcessTags"
      assert unpacker.unpackArrayHeader() == processTags.size()
      processTags.each {
        assert unpacker.unpackString() == it
      }
    }

    return true
  }
}


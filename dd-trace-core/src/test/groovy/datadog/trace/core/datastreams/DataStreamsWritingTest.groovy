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
    sharedCommObjects.okHttpClient = testOkhttpClient
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

    assert unpacker.unpackMapHeader() == 8
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
    sharedCommObjects.okHttpClient = testOkhttpClient
    sharedCommObjects.createRemaining(fakeConfig)

    def timeSource = new ControllableTimeSource()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(fakeConfig, sharedCommObjects, timeSource, { traceConfig })
    dataStreams.start()
    dataStreams.add(new StatsPoint(DataStreamsTags.create(null, null), 9, 0, 10, timeSource.currentTimeNanos, 0, 0, 0, null))
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.Inbound, "testTopic", "testGroup", null), 1, 2, 5, timeSource.currentTimeNanos, 0, 0, 0, null))
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 100)
    dataStreams.trackBacklog(DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 130)
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100l)
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.Inbound, "testTopic", "testGroup", null), 1, 2, 5, timeSource.currentTimeNanos, SECONDS.toNanos(10), SECONDS.toNanos(10), 10, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.Inbound, "testTopic", "testGroup", null), 1, 2, 5, timeSource.currentTimeNanos, SECONDS.toNanos(5), SECONDS.toNanos(5), 5, null))
    dataStreams.add(new StatsPoint(DataStreamsTags.create("testType", DataStreamsTags.Direction.Inbound, "testTopic2", "testGroup", null), 3, 4, 6, timeSource.currentTimeNanos, SECONDS.toNanos(2), 0, 2, null))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.close()

    then:
    conditions.eventually {
      assert requestBodies.size() == 1
    }

    validateMessage(requestBodies[0], processTagsEnabled)

    cleanup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()

    where:
    processTagsEnabled << [true, false]
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
      if (mapHeaderSize == 5) {  // empty topic group
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
      } else { //other group
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


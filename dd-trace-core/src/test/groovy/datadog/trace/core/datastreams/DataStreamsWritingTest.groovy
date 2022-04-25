package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.http.OkHttpUtils
import datadog.trace.api.Config
import datadog.trace.api.WellKnownTags
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.core.DDTraceCoreInfo
import datadog.trace.core.test.DDCoreSpecification
import okhttp3.HttpUrl
import okio.BufferedSource
import okio.GzipSource
import okio.Okio
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import spock.lang.AutoCleanup
import spock.lang.Requires
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.DEFAULT_BUCKET_DURATION_NANOS
import static java.util.concurrent.TimeUnit.SECONDS


/**
 * This test class exists because a real integration test is not possible. see DataStreamsIntegrationTest
 */
@Requires({
  jvm.isJava8Compatible()
})
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

  def setup() {
    requestBodies = []
  }

  def "Write bucket to mock server"() {
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

    when:
    def checkpointer = new DefaultDataStreamsCheckpointer(fakeConfig, sharedCommObjects, timeSource)
    checkpointer.start()
    checkpointer.accept(new StatsPoint([], 9, 0, timeSource.currentTimeNanos, 0, 0))
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
      assert requestBodies.size() == 1
    }
    validateMessage(requestBodies[0])

    cleanup:
    checkpointer.close()
  }

  def validateMessage(byte[] message) {
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(message)))

    BufferedSource bufferedSource = Okio.buffer(gzipSource)
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream())

    assert unpacker.unpackMapHeader() == 6
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
    assert unpacker.unpackString() == "Stats"
    assert unpacker.unpackArrayHeader() == 2  // 2 time buckets

    // FIRST BUCKET
    assert unpacker.unpackMapHeader() == 3
    assert unpacker.unpackString() == "Start"
    unpacker.skipValue()
    assert unpacker.unpackString() == "Duration"
    assert unpacker.unpackLong() == DEFAULT_BUCKET_DURATION_NANOS
    assert unpacker.unpackString() == "Stats"
    assert unpacker.unpackArrayHeader() == 2 // 2 groups in first bucket

    Set availableSizes = [4, 5] // we don't know the order the groups will be reported
    2.times {
      int mapHeaderSize = unpacker.unpackMapHeader()
      assert availableSizes.remove(mapHeaderSize)
      if (mapHeaderSize == 4) {  // empty topic group
        assert unpacker.unpackString() == "PathwayLatency"
        unpacker.skipValue()
        assert unpacker.unpackString() == "EdgeLatency"
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
        assert unpacker.unpackString() == "Hash"
        assert unpacker.unpackLong() == 1
        assert unpacker.unpackString() == "ParentHash"
        assert unpacker.unpackLong() == 2
        assert unpacker.unpackString() == "EdgeTags"
        assert unpacker.unpackArrayHeader() == 3
        assert unpacker.unpackString() == "type:testType"
        assert unpacker.unpackString() == "group:testGroup"
        assert unpacker.unpackString() == "topic:testTopic"
      }
    }

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
      assert unpacker.unpackMapHeader() == 5
      assert unpacker.unpackString() == "PathwayLatency"
      unpacker.skipValue()
      assert unpacker.unpackString() == "EdgeLatency"
      unpacker.skipValue()
      assert unpacker.unpackString() == "Hash"
      def hash = unpacker.unpackLong()
      assert availableHashes.remove(hash)
      assert unpacker.unpackString() == "ParentHash"
      assert unpacker.unpackLong() == (hash == 1 ? 2 : 4)
      assert unpacker.unpackString() == "EdgeTags"
      assert unpacker.unpackArrayHeader() == 3
      assert unpacker.unpackString() == "type:testType"
      assert unpacker.unpackString() == "group:testGroup"
      assert unpacker.unpackString() == (hash == 1 ? "topic:testTopic" : "topic:testTopic2")
    }

    return true
  }
}


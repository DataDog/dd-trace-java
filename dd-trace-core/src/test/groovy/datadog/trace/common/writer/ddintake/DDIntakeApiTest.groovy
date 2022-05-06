package datadog.trace.common.writer.ddintake

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.DDId
import datadog.trace.api.WellKnownTags
import datadog.trace.api.intake.TrackType
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.Payload
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification
import okhttp3.HttpUrl
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Timeout

import java.nio.ByteBuffer

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

@Timeout(20)
class DDIntakeApiTest extends DDCoreSpecification {

  static WellKnownTags wellKnownTags = new WellKnownTags("my-runtime-id", "my-hostname", "my-env","my-service","my-version","my-language")
  static String apiKey = "my-secret-apikey"
  static msgPackMapper = new ObjectMapper(new MessagePackFactory())

  static newIntake(String path) {
    httpServer {
      handlers {
        post(path) {
          if (request.contentType != "application/msgpack") {
            response.status(400).send("wrong type: $request.contentType")
          } else {
            response.status(200).send()
          }
        }
      }
    }
  }

  def "sending an empty list of traces returns no errors"() {
    setup:
    def path = buildIntakePath(trackType, apiVersion)
    def intake = newIntake(path)
    def client = createIntakeApi(intake.address.toString(), trackType)
    def payload = prepareTraces(trackType, [])

    expect:
    def response = client.sendSerializedTraces(payload)
    response.success()
    response.status() == 200
    intake.getLastRequest().path == path

    cleanup:
    intake.close()

    where:
    trackType | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "content is sent as MSGPACK"() {
    setup:
    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
          response.send()
        }
      }
    }

    def client = createIntakeApi(intake.address.toString(), trackType)
    def payload = prepareTraces(trackType, traces)

    expect:
    client.sendSerializedTraces(payload).status()
    intake.lastRequest.contentType == "application/msgpack"
    convertMap(intake.lastRequest.body) == expectedRequestBody

    cleanup:
    intake.close()

    where:
    // spotless:off
    trackType             | apiVersion | traces | expectedRequestBody
    TrackType.CITESTCYCLE | "v2"       | []     | [:]
    TrackType.CITESTCYCLE | "v2"       | [[buildSpan(1L, "service.name", "my-service")]] | new TreeMap<>([
      "version":1,
      "metadata": new TreeMap<>([
        "*":new TreeMap<>([
          "env":"my-env",
          "runtime-id":"my-runtime-id",
          "language":"my-language"
        ])]),
       "events":[new TreeMap<>([
         "type":"span",
         "version":1,
         "content":new TreeMap<>([
           "service":"my-service",
           "name":"fakeOperation",
           "resource":"fakeResource",
           "error":0,
           "trace_id":1L,
           "span_id":1L,
           "parent_id":0L,
           "start":1000L,
           "duration":10L,
           "meta": [:],
           "metrics":[:]
         ])
       ])]
      ])
    // spotless:on
    ignore = traces.each {
      it.each {
        it.finish()
        it.@durationNano = 10
      }
    }
  }

  static Map<String, Object> convertMap(byte[] bytes) {
    return msgPackMapper.readValue(bytes, new TypeReference<TreeMap<String, Object>>() {})
  }

  static class Traces implements ByteBufferConsumer {
    int traceCount
    ByteBuffer buffer

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      this.buffer = buffer
      this.traceCount = messageCount
    }
  }

  def createIntakeApi(String url, TrackType trackType) {
    HttpUrl hostUrl = HttpUrl.get(url)
    return DDIntakeApi.builder().hostUrl(hostUrl).trackType(trackType).apiKey(apiKey).build()
  }

  def discoverMapper(TrackType trackType) {
    def mapperDiscover = new DDIntakeMapperDiscovery(trackType, wellKnownTags)
    mapperDiscover.discover()
    return mapperDiscover.getMapper()
  }

  def buildIntakePath(TrackType trackType, String apiVersion) {
    return String.format("/api/%s/%s", apiVersion, trackType.name().toLowerCase())
  }

  Payload prepareTraces(TrackType trackType, List<List<DDSpan>> traces) {
    Traces traceCapture = new Traces()
    def packer = new MsgPackWriter(new FlushingBuffer(1 << 20, traceCapture))
    def mapper = discoverMapper(trackType)
    for(trace in traces) {
      packer.format(trace, mapper)
    }
    packer.flush()
    return mapper.newPayload()
      .withBody(traceCapture.traceCount,
      traces.isEmpty() ? ByteBuffer.allocate(0) : traceCapture.buffer)
  }

  DDSpan buildSpan(long timestamp, String tag, String value) {
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.SAMPLER_KEEP,
      SamplingMechanism.UNKNOWN,
      null,
      [:],
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.from(1)),
      null,
      AgentTracer.NoopPathwayContext.INSTANCE,
      false)

    def span = DDSpan.create(timestamp, context)
    span.setTag(tag, value)

    tracer.close()
    return span
  }
}

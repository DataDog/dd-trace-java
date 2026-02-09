package datadog.trace.common.writer.ddintake

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.http.client.HttpUrl
import datadog.trace.api.DDTags
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags
import datadog.trace.api.intake.TrackType
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.Payload
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification
import org.apache.commons.io.IOUtils
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

@Timeout(20)
class DDIntakeApiTest extends DDCoreSpecification {

  static CiVisibilityWellKnownTags wellKnownTags = new CiVisibilityWellKnownTags(
  "my-runtime-id", "my-env", "my-language",
  "my-runtime-name", "my-runtime-version", "my-runtime-vendor",
  "my-os-arch", "my-os-platform", "my-os-version", "false")

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
    def clientResponse = client.sendSerializedTraces(payload)
    clientResponse.success()
    clientResponse.status().present
    clientResponse.status().asInt == 200
    intake.getLastRequest().path == path

    cleanup:
    intake.close()

    where:
    trackType             | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "retries when backend returns 5xx"() {
    setup:
    def retry = 1
    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
          if (retry < 5) {
            response.status(503).send()
            retry += 1
          } else {
            response.status(200).send()
          }
        }
      }
    }

    def client = createIntakeApi(intake.address.toString(), trackType)
    def payload = prepareTraces(trackType, [])

    expect:
    def clientResponse = client.sendSerializedTraces(payload)
    clientResponse.success()
    clientResponse.status().present
    clientResponse.status().asInt == 200
    intake.getLastRequest().path == path

    cleanup:
    intake.close()

    where:
    trackType             | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "retries when backend returns 429 Too Many Requests"() {
    setup:
    def retry = 0
    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
          if (retry < 1) {
            response.status(429).addHeader("x-ratelimit-reset", "0").send()
            retry += 1
          } else {
            response.status(200).send()
          }
        }
      }
    }

    def client = createIntakeApi(intake.address.toString(), trackType)
    def payload = prepareTraces(trackType, [])

    expect:
    def clientResponse = client.sendSerializedTraces(payload)
    clientResponse.success()
    clientResponse.status().present
    clientResponse.status().asInt == 200
    intake.getLastRequest().path == path

    cleanup:
    intake.close()

    where:
    trackType             | apiVersion
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
    client.sendSerializedTraces(payload).status().present
    intake.lastRequest.contentType == "application/msgpack"
    convertMap(intake.lastRequest.body) == expectedRequestBody

    cleanup:
    intake.close()

    where:
    // spotless:off
    trackType             | apiVersion | traces                                                                                               | expectedRequestBody
    TrackType.CITESTCYCLE | "v2"       | []                                                                                                   | [:]
    TrackType.CITESTCYCLE | "v2"       | [[buildSpan(1L, "fakeType", ["service.name": "my-service"])]]                                        | new TreeMap<>([
      "version" : 1,
      "metadata": new TreeMap<>([
        "*": new TreeMap<>([
          "env"                                 : "my-env",
          "runtime-id"                          : "my-runtime-id",
          "language"                            : "my-language",
          (Tags.RUNTIME_NAME)                   : "my-runtime-name",
          (Tags.RUNTIME_VERSION)                : "my-runtime-version",
          (Tags.RUNTIME_VENDOR)                 : "my-runtime-vendor",
          (Tags.OS_ARCHITECTURE)                : "my-os-arch",
          (Tags.OS_PLATFORM)                    : "my-os-platform",
          (Tags.OS_VERSION)                     : "my-os-version",
          (DDTags.TEST_IS_USER_PROVIDED_SERVICE): "false"
        ])]),
      "events"  : [new TreeMap<>([
        "type"   : "span",
        "version": 1,
        "content": new TreeMap<>([
          "service"  : "my-service",
          "name"     : "fakeOperation",
          "resource" : "fakeResource",
          "error"    : 0,
          "trace_id" : 1L,
          "span_id"  : 1L,
          "parent_id": 0L,
          "start"    : 1000L,
          "duration" : 10L,
          "meta"     : [:],
          "metrics"  : [:]
        ])
      ])]
    ])
    TrackType.CITESTCYCLE | "v2"       | [[buildSpan(1L, InternalSpanTypes.TEST, ["test_suite_id": 123L, "test_module_id": 456L])]]           | new TreeMap<>([
      "version" : 1,
      "metadata": new TreeMap<>([
        "*": new TreeMap<>([
          "env"                                 : "my-env",
          "runtime-id"                          : "my-runtime-id",
          "language"                            : "my-language",
          (Tags.RUNTIME_NAME)                   : "my-runtime-name",
          (Tags.RUNTIME_VERSION)                : "my-runtime-version",
          (Tags.RUNTIME_VENDOR)                 : "my-runtime-vendor",
          (Tags.OS_ARCHITECTURE)                : "my-os-arch",
          (Tags.OS_PLATFORM)                    : "my-os-platform",
          (Tags.OS_VERSION)                     : "my-os-version",
          (DDTags.TEST_IS_USER_PROVIDED_SERVICE): "false"
        ])]),
      "events"  : [new TreeMap<>([
        "type"   : "test",
        "version": 2,
        "content": new TreeMap<>([
          "test_suite_id" : 123L,
          "test_module_id": 456L,
          "service"       : "fakeService",
          "name"          : "fakeOperation",
          "resource"      : "fakeResource",
          "error"         : 0,
          "trace_id"      : 1L,
          "span_id"       : 1L,
          "parent_id"     : 0L,
          "start"         : 1000L,
          "duration"      : 10L,
          "meta"          : [:],
          "metrics"       : [:]
        ])
      ])]
    ])
    TrackType.CITESTCYCLE | "v2"       | [[buildSpan(1L, InternalSpanTypes.TEST_SUITE_END, ["test_suite_id": 123L, "test_module_id": 456L])]] | new TreeMap<>([
      "version" : 1,
      "metadata": new TreeMap<>([
        "*": new TreeMap<>([
          "env"                                 : "my-env",
          "runtime-id"                          : "my-runtime-id",
          "language"                            : "my-language",
          (Tags.RUNTIME_NAME)                   : "my-runtime-name",
          (Tags.RUNTIME_VERSION)                : "my-runtime-version",
          (Tags.RUNTIME_VENDOR)                 : "my-runtime-vendor",
          (Tags.OS_ARCHITECTURE)                : "my-os-arch",
          (Tags.OS_PLATFORM)                    : "my-os-platform",
          (Tags.OS_VERSION)                     : "my-os-version",
          (DDTags.TEST_IS_USER_PROVIDED_SERVICE): "false"
        ])]),
      "events"  : [new TreeMap<>([
        "type"   : "test_suite_end",
        "version": 1,
        "content": new TreeMap<>([
          "test_suite_id" : 123L,
          "test_module_id": 456L,
          "service"       : "fakeService",
          "name"          : "fakeOperation",
          "resource"      : "fakeResource",
          "error"         : 0,
          "start"         : 1000L,
          "duration"      : 10L,
          "meta"          : [:],
          "metrics"       : [:]
        ])
      ])]
    ])
    TrackType.CITESTCYCLE | "v2"       | [[buildSpan(1L, InternalSpanTypes.TEST_MODULE_END, ["test_module_id": 456L])]]                       | new TreeMap<>([
      "version" : 1,
      "metadata": new TreeMap<>([
        "*": new TreeMap<>([
          "env"                                 : "my-env",
          "runtime-id"                          : "my-runtime-id",
          "language"                            : "my-language",
          (Tags.RUNTIME_NAME)                   : "my-runtime-name",
          (Tags.RUNTIME_VERSION)                : "my-runtime-version",
          (Tags.RUNTIME_VENDOR)                 : "my-runtime-vendor",
          (Tags.OS_ARCHITECTURE)                : "my-os-arch",
          (Tags.OS_PLATFORM)                    : "my-os-platform",
          (Tags.OS_VERSION)                     : "my-os-version",
          (DDTags.TEST_IS_USER_PROVIDED_SERVICE): "false"
        ])]),
      "events"  : [new TreeMap<>([
        "type"   : "test_module_end",
        "version": 1,
        "content": new TreeMap<>([
          "test_module_id": 456L,
          "service"       : "fakeService",
          "name"          : "fakeOperation",
          "resource"      : "fakeResource",
          "error"         : 0,
          "start"         : 1000L,
          "duration"      : 10L,
          "meta"          : [:],
          "metrics"       : [:]
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
    return msgPackMapper.readValue(decompress(bytes), new TypeReference<TreeMap<String, Object>>() {})
  }

  static byte[] decompress(byte[] bytes) {
    def baos = new ByteArrayOutputStream()
    try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      IOUtils.copy(zip, baos)
    }
    return baos.toByteArray()
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
    HttpUrl hostUrl = HttpUrl.parse(url)
    return DDIntakeApi.builder().hostUrl(hostUrl).trackType(trackType).apiKey(apiKey).build()
  }

  def discoverMapper(TrackType trackType) {
    def mapperDiscover = new DDIntakeMapperDiscovery(trackType, wellKnownTags, true)
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
    for (trace in traces) {
      packer.format(trace, mapper)
    }
    packer.flush()
    return mapper.newPayload()
    .withBody(traceCapture.traceCount,
    traces.isEmpty() ? ByteBuffer.allocate(0) : traceCapture.buffer)
  }
}

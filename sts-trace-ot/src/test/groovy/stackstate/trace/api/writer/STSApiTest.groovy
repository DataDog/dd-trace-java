package stackstate.trace.api.writer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import stackstate.opentracing.SpanFactory
import stackstate.trace.common.writer.STSApi
import stackstate.trace.common.writer.STSApi.ResponseListener
import org.msgpack.jackson.dataformat.MessagePackFactory
import ratpack.exec.Blocking
import ratpack.http.Headers
import ratpack.http.MediaType
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import static ratpack.groovy.test.embed.GroovyEmbeddedApp.ratpack

class STSApiTest extends Specification {
  static mapper = new ObjectMapper(new MessagePackFactory())

  def "sending an empty list of traces returns no errors"() {
    setup:
    def agent = ratpack {
      handlers {
        put("v0.4/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
      }
    }
    def client = new STSApi("localhost", agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${agent.address.port}/v0.4/traces"
    client.sendTraces([])

    cleanup:
    agent.close()
  }

  def "non-200 response results in false returned"() {
    setup:
    def agent = ratpack {
      handlers {
        put("v0.4/traces") {
          response.status(404).send()
        }
      }
    }
    def client = new STSApi("localhost", agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${agent.address.port}/v0.3/traces"
    !client.sendTraces([])

    cleanup:
    agent.close()
  }

  def "content is sent as MSGPACK"() {
    setup:
    def requestContentType = new AtomicReference<MediaType>()
    def requestHeaders = new AtomicReference<Headers>()
    def requestBody = new AtomicReference<byte[]>()
    def agent = ratpack {
      handlers {
        put("v0.4/traces") {
          requestContentType.set(request.contentType)
          requestHeaders.set(request.headers)
          request.body.then {
            requestBody.set(it.bytes)
            response.send()
          }
        }
      }
    }
    def client = new STSApi("localhost", agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${agent.address.port}/v0.4/traces"
    client.sendTraces(traces)
    requestContentType.get().type == "application/msgpack"
    requestHeaders.get().get("Stackstate-Meta-Lang") == "java"
    requestHeaders.get().get("Stackstate-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    requestHeaders.get().get("Stackstate-Meta-Tracer-Version") == "Stubbed-Test-Version"
    requestHeaders.get().get("X-Datadog-Trace-Count") == "${traces.size()}"
    convertList(requestBody.get()) == expectedRequestBody

    cleanup:
    agent.close()

    // Populate thread info dynamically as it is different when run via gradle vs idea.
    where:
    traces                                                               | expectedRequestBody
    []                                                                   | []
    [SpanFactory.newSpanOf(1L).setTag("service.name", "my-service")]     | [new TreeMap<>([
      "duration" : 0,
      "error"    : 0,
      "meta"     : ["span.type": "fakeType", "span.hostname": "fakehost", "span.pid": "42", "thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [:],
      "name"     : "fakeOperation",
      "parent_id": 0,
      "resource" : "fakeResource",
      "service"  : "my-service",
      "span_id"  : 1,
      "start"    : 1000,
      "trace_id" : 1,
      "type"     : "fakeType"
    ])]
    [SpanFactory.newSpanOf(100L).setTag("resource.name", "my-resource")] | [new TreeMap<>([
      "duration" : 0,
      "error"    : 0,
      "meta"     : ["span.type": "fakeType", "span.hostname": "fakehost", "span.pid": "42", "thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [:],
      "name"     : "fakeOperation",
      "parent_id": 0,
      "resource" : "my-resource",
      "service"  : "fakeService",
      "span_id"  : 1,
      "start"    : 100000,
      "trace_id" : 1,
      "type"     : "fakeType"
    ])]
  }

  def "Api ResponseListeners see 200 responses"() {
    setup:
    def agentResponse = new AtomicReference<String>(null)
    def requestHeaders = new AtomicReference<Headers>()
    ResponseListener responseListener = { String endpoint, JsonNode responseJson ->
      agentResponse.set(responseJson.toString())
    }
    def agent = ratpack {
      handlers {
        put("v0.4/traces") {
          requestHeaders.set(request.headers)
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send('{"hello":"test"}')
        }
      }
    }
    def client = new STSApi("localhost", agent.address.port)
    client.addResponseListener(responseListener)
    def traceCounter = new AtomicInteger(3)
    client.addTraceCounter(traceCounter)

    when:
    client.sendTraces([])
    then:
    agentResponse.get() == '{"hello":"test"}'
    requestHeaders.get().get("Datadog-Meta-Lang") == "java"
    requestHeaders.get().get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    requestHeaders.get().get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    requestHeaders.get().get("X-Datadog-Trace-Count") == "3" // false data shows the value provided via traceCounter.
    traceCounter.get() == 0

    cleanup:
    agent.close()
  }

  def "Api Downgrades to v3 if v0.4 not available"() {
    setup:
    def v3Agent = ratpack {
      handlers {
        put("v0.3/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
      }
    }
    def client = new STSApi("localhost", v3Agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${v3Agent.address.port}/v0.3/traces"
    client.sendTraces([])

    cleanup:
    v3Agent.close()
  }

  def "Api Downgrades to v3 if timeout exceeded (#delayTrace, #badPort)"() {
    // This test is unfortunately only exercising the read timeout, not the connect timeout.
    setup:
    def agent = ratpack {
      handlers {
        put("v0.3/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
        put("v0.4/traces") {
          Blocking.exec {
            Thread.sleep(delayTrace)
            def status = request.contentLength > 0 ? 200 : 500
            response.status(status).send()
          }
        }
      }
    }
    def port = badPort ? 999 : agent.address.port
    def client = new STSApi("localhost", port)

    expect:
    client.tracesEndpoint == "http://localhost:${port}/$endpointVersion/traces"

    cleanup:
    agent.close()

    where:
    endpointVersion | delayTrace | badPort
    "v0.4"          | 0          | false
    "v0.3"          | 0          | true
    "v0.4"          | 500        | false
    "v0.3"          | 30000      | false
  }

  static List<TreeMap<String, Object>> convertList(byte[] bytes) {
    return mapper.readValue(bytes, new TypeReference<List<TreeMap<String, Object>>>() {})
  }

  static TreeMap<String, Object> convertMap(byte[] bytes) {
    return mapper.readValue(bytes, new TypeReference<TreeMap<String, Object>>() {})
  }
}

package datadog.trace.api.writer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.opentracing.SpanFactory
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.DDAgentResponseListener
import datadog.trace.util.test.DDSpecification
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Timeout

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

@Timeout(20)
class DDAgentApiTest extends DDSpecification {
  static mapper = new ObjectMapper(new MessagePackFactory())

  def "sending an empty list of traces returns no errors"() {
    setup:
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          if (request.contentType != "application/msgpack") {
            response.status(400).send("wrong type: $request.contentType")
          } else if (request.contentLength <= 0) {
            response.status(400).send("no content")
          } else {
            response.status(200).send()
          }
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null)

    expect:
    def response = client.sendTraces([])
    response.success()
    response.status() == 200
    agent.getLastRequest().path == "/v0.4/traces"

    cleanup:
    agent.close()
  }

  def "non-200 response"() {
    setup:
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          response.status(404).send()
        }

        put("v0.3/traces") {
          response.status(404).send()
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null)

    expect:
    def response = client.sendTraces([])
    !response.success()
    response.status() == 404
    agent.getLastRequest().path == "/v0.3/traces"

    cleanup:
    agent.close()
  }

  def "content is sent as MSGPACK"() {
    setup:
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          response.send()
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null)

    expect:
    client.sendTraces(traces).success()
    agent.lastRequest.contentType == "application/msgpack"
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "${traces.size()}"
    convertList(agent.lastRequest.body) == expectedRequestBody

    cleanup:
    agent.close()

    // Populate thread info dynamically as it is different when run via gradle vs idea.
    where:
    traces                                                                 | expectedRequestBody
    []                                                                     | []
    [[SpanFactory.newSpanOf(1L).setTag("service.name", "my-service")]]     | [[new TreeMap<>([
      "duration" : 0,
      "error"    : 0,
      "meta"     : ["thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [:],
      "name"     : "fakeOperation",
      "parent_id": 0,
      "resource" : "fakeResource",
      "service"  : "my-service",
      "span_id"  : 1,
      "start"    : 1000,
      "trace_id" : 1,
      "type"     : "fakeType"
    ])]]
    [[SpanFactory.newSpanOf(100L).setTag("resource.name", "my-resource")]] | [[new TreeMap<>([
      "duration" : 0,
      "error"    : 0,
      "meta"     : ["thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [:],
      "name"     : "fakeOperation",
      "parent_id": 0,
      "resource" : "my-resource",
      "service"  : "fakeService",
      "span_id"  : 1,
      "start"    : 100000,
      "trace_id" : 1,
      "type"     : "fakeType"
    ])]]
  }

  def "Api ResponseListeners see 200 responses"() {
    setup:
    def agentResponse = new AtomicReference<Map>(null)
    DDAgentResponseListener responseListener = { String endpoint, Map responseJson ->
      agentResponse.set(responseJson)
    }
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send('{"hello":{}}')
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null)
    client.addResponseListener(responseListener)

    when:
    client.sendTraces([[], [], []])
    then:
    agentResponse.get() == ["hello": [:]]
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "3" // false data shows the value provided via traceCounter.

    cleanup:
    agent.close()
  }

  def "Api Downgrades to v3 if v0.4 not available"() {
    setup:
    def v3Agent = httpServer {
      handlers {
        put("v0.3/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
      }
    }
    def client = new DDAgentApi("localhost", v3Agent.address.port, null)

    expect:
    client.sendTraces([]).success()
    v3Agent.getLastRequest().path == "/v0.3/traces"

    cleanup:
    v3Agent.close()
  }

  def "Api Downgrades to v3 if timeout exceeded (#delayTrace, #badPort)"() {
    // This test is unfortunately only exercising the read timeout, not the connect timeout.
    setup:
    def agent = httpServer {
      handlers {
        put("v0.3/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
        put("v0.4/traces") {
          Thread.sleep(delayTrace)
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
      }
    }
    def port = badPort ? 999 : agent.address.port
    def client = new DDAgentApi("localhost", port, null)
    def result = client.sendTraces([])

    expect:
    result.success() == !badPort // Expect success of port is ok
    if (!badPort) {
      assert agent.getLastRequest().path == "/$endpointVersion/traces"
    }

    cleanup:
    agent.close()

    where:
    endpointVersion | delayTrace | badPort
    "v0.4"          | 0          | false
    "v0.3"          | 0          | true
    "v0.4"          | 500        | false
    "v0.3"          | 30000      | false
  }

  def "verify content length"() {
    setup:
    def receivedContentLength = new AtomicLong()
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          receivedContentLength.set(request.contentLength)
          response.status(200).send()
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null)

    when:
    def success = client.sendTraces(traces).success()
    then:
    success
    receivedContentLength.get() == expectedLength

    cleanup:
    agent.close()

    where:
    expectedLength | traces
    1              | []
    3              | [[], []]
    16             | (1..15).collect { [] }
    19             | (1..16).collect { [] }
    65538          | (1..((1 << 16) - 1)).collect { [] }
    65541          | (1..(1 << 16)).collect { [] }
  }

  static List<List<TreeMap<String, Object>>> convertList(byte[] bytes) {
    return mapper.readValue(bytes, new TypeReference<List<List<TreeMap<String, Object>>>>() {})
  }
}

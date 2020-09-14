package datadog.trace.common.writer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.DDAgentResponseListener
import datadog.trace.common.writer.ddagent.Payload
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.SpanFactory
import datadog.trace.core.monitor.Monitoring
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.Packer
import datadog.trace.util.test.DDSpecification
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Shared
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

@Timeout(20)
class DDAgentApiTest extends DDSpecification {

  @Shared
  Monitoring monitoring = new Monitoring(new NoOpStatsDClient(), 1, TimeUnit.SECONDS)
  static mapper = new ObjectMapper(new MessagePackFactory())

  static newAgent(String latestVersion) {
    httpServer {
      handlers {
        put(latestVersion) {
          System.out.println(request.getHeader("X-Datadog-Trace-Count"))
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
  }

  def "sending an empty list of traces returns no errors"() {
    setup:
    def agent = newAgent(agentVersion)
    def client = new DDAgentApi("localhost", agent.address.port, null, 1000, monitoring)
    def payload = prepareTraces(agentVersion, [])

    expect:
    def response = client.sendSerializedTraces(payload)
    response.success()
    response.status() == 200
    agent.getLastRequest().path == "/" + agentVersion

    cleanup:
    agent.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "get right mapper for latest endpoint"() {
    setup:
    def agent = newAgent(version)
    def client = new DDAgentApi("localhost", agent.address.port, null, 1000, monitoring)
    def mapper = client.selectTraceMapper()
    expect:
    mapper.getClass().isAssignableFrom(expected)
    agent.getLastRequest().path == "/" + version

    cleanup:
    agent.close()

    where:
    version       | expected
    "v0.5/traces" | TraceMapperV0_5
    "v0.4/traces" | TraceMapperV0_4
    "v0.3/traces" | TraceMapperV0_4
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
    def client = new DDAgentApi("localhost", agent.address.port, null, 1000, monitoring)
    Payload payload = prepareTraces("v0.3/traces", [])
    expect:
    def response = client.sendSerializedTraces(payload)
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
        put(agentVersion) {
          response.send()
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null, 1000, monitoring)
    def payload = prepareTraces(agentVersion, traces)

    expect:
    client.sendSerializedTraces(payload).success()
    agent.lastRequest.contentType == "application/msgpack"
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "${traces.size()}"
    convertList(agentVersion, agent.lastRequest.body) == expectedRequestBody

    cleanup:
    agent.close()

    // Populate thread info dynamically as it is different when run via gradle vs idea.
    where:
    traces                                                                 | expectedRequestBody
    []                                                                     | []
    [[SpanFactory.newSpanOf(1L).setTag("service.name", "my-service")]]     | [[new TreeMap<>([
      "duration" : 10,
      "error"    : 0,
      "meta"     : ["thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [
        (RateByServiceSampler.SAMPLING_AGENT_RATE): 1.0,
        (DDSpanContext.PRIORITY_SAMPLING_KEY)     : 1,
      ],
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
      "duration" : 10,
      "error"    : 0,
      "meta"     : ["thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [
        (RateByServiceSampler.SAMPLING_AGENT_RATE): 1.0,
        (DDSpanContext.PRIORITY_SAMPLING_KEY)     : 1,
      ],
      "name"     : "fakeOperation",
      "parent_id": 0,
      "resource" : "my-resource",
      "service"  : "fakeService",
      "span_id"  : 1,
      "start"    : 100000,
      "trace_id" : 1,
      "type"     : "fakeType"
    ])]]

    ignore = traces.each {
      it.each {
        it.finish()
        it.@durationNano.set(10)
      }
    }
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.4/traces"]
  }

  def "Api ResponseListeners see 200 responses"() {
    setup:
    def agentResponse = new AtomicReference<Map>(null)
    DDAgentResponseListener responseListener = { String endpoint, Map responseJson ->
      agentResponse.set(responseJson)
    }
    def agent = httpServer {
      handlers {
        put(agentVersion) {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send('{"hello":{}}')
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null, 1000, monitoring)
    client.addResponseListener(responseListener)
    def payload = prepareTraces(agentVersion, [[], [], []])

    when:
    client.sendSerializedTraces(payload)
    then:
    agentResponse.get() == ["hello": [:]]
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "3" // false data shows the value provided via traceCounter.

    cleanup:
    agent.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
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
    def client = new DDAgentApi("localhost", v3Agent.address.port, null, 1000, monitoring)
    def payload = prepareTraces("v0.4/traces", [])
    expect:
    client.sendSerializedTraces(payload).success()
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
    def client = new DDAgentApi("localhost", port, null, 1000, monitoring)
    def payload = prepareTraces("v0.4/traces", [])
    def result = client.sendSerializedTraces(payload)

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
        put(agentVersion) {
          receivedContentLength.set(request.contentLength)
          response.status(200).send()
        }
      }
    }
    def client = new DDAgentApi("localhost", agent.address.port, null, 1000, monitoring)
    def payload = prepareTraces(agentVersion, traces)
    when:
    def success = client.sendSerializedTraces(payload).success()
    then:
    success
    receivedContentLength.get() == expectedLength

    cleanup:
    agent.close()

    // all the tested traces are empty (why?) and it just so happens that
    // arrays and maps take the same amount of space in messagepack, so
    // all the sizes match, except in v0.5 where there is 1 byte for a
    // 2 element array header and 1 byte for an empty dictionary
    where:
    agentVersion  | expectedLength | traces
    "v0.4/traces" | 1              | []
    "v0.4/traces" | 3              | [[], []]
    "v0.4/traces" | 16             | (1..15).collect { [] }
    "v0.4/traces" | 19             | (1..16).collect { [] }
    "v0.4/traces" | 65538          | (1..((1 << 16) - 1)).collect { [] }
    "v0.4/traces" | 65541          | (1..(1 << 16)).collect { [] }
    "v0.5/traces" | 1 + 1 + 1      | []
    "v0.5/traces" | 3 + 1 + 1      | [[], []]
    "v0.5/traces" | 16 + 1 + 1     | (1..15).collect { [] }
    "v0.5/traces" | 19 + 1 + 1     | (1..16).collect { [] }
    "v0.5/traces" | 65538 + 1 + 1  | (1..((1 << 16) - 1)).collect { [] }
    "v0.5/traces" | 65541 + 1 + 1  | (1..(1 << 16)).collect { [] }
  }

  def "Embedded HTTP client rejects async requests"() {
    setup:
    def agent = newAgent("v0.5/traces")
    def client = new DDAgentApi("localhost", agent.address.port, null, 1000, monitoring)
    def payload = prepareTraces("v0.5/traces", [])
    def result = client.sendSerializedTraces(payload)
    def httpExecutorService = client.httpClient.dispatcher().executorService()
    when:
    httpExecutorService.execute({})
    then:
    thrown RejectedExecutionException
    and:
    httpExecutorService.isShutdown()
    cleanup:
    agent.close()
  }

  static List<List<TreeMap<String, Object>>> convertList(String agentVersion, byte[] bytes) {
    if (agentVersion.equals("v0.5/traces")) {
      return convertListV5(bytes)
    }
    return mapper.readValue(bytes, new TypeReference<List<List<TreeMap<String, Object>>>>() {})
  }

  static List<List<TreeMap<String, Object>>> convertListV5(byte[] bytes) {
    List<List<List<Object>>> traces = mapper.readValue(bytes, new TypeReference<List<List<List<Object>>>>() {})
    List<List<TreeMap<String, Object>>> maps = new ArrayList<>(traces.size())
    for (List<List<Object>> trace : traces) {
      List<TreeMap<String, Object>> mapTrace = new ArrayList<>()
      for (List<Object> span : trace) {
        TreeMap<String, Object> map = new TreeMap<>()
        if (!span.isEmpty()) {
          map.put("service", span.get(0))
          map.put("name", span.get(1))
          map.put("resource", span.get(2))
          map.put("trace_id", span.get(3))
          map.put("span_id", span.get(4))
          map.put("parent_id", span.get(5))
          map.put("start", span.get(6))
          map.put("duration", span.get(7))
          map.put("error", span.get(8))
          map.put("meta", span.get(9))
          map.put("metrics", span.get(10))
          map.put("type", span.get(11))
        }
        mapTrace.add(map)
      }
      maps.add(mapTrace)
    }
    return maps
  }

  Payload prepareTraces(String agentVersion, List<List<DDSpan>> traces) {
    ByteBuffer buffer = ByteBuffer.allocate(1 << 20)
    Traces traceCapture = new Traces()
    def packer = new Packer(traceCapture, buffer)
    def traceMapper = agentVersion.equals("v0.5/traces")
      ? new TraceMapperV0_5()
      : new TraceMapperV0_4()
    for (trace in traces) {
      packer.format(trace, traceMapper)
    }
    packer.flush()
    return traceMapper.newPayload()
      .withBody(traceCapture.traceCount, traceCapture.buffer)
      .withRepresentativeCount(traceCapture.representativeCount)
  }

  static class Traces implements ByteBufferConsumer {
    int traceCount
    int representativeCount
    ByteBuffer buffer

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      this.buffer = buffer
      this.representativeCount = messageCount
      this.traceCount = messageCount
    }
  }
}

package datadog.trace.common.writer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.OkHttpUtils
import datadog.communication.serialization.ByteBufferConsumer
import datadog.metrics.api.Monitoring
import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.api.statsd.StatsDClient
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.Config
import datadog.trace.api.ProcessTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.common.sampling.RateByServiceTraceSampler
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.common.writer.ddagent.TraceMapperV1_0
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
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
class DDAgentApiTest extends DDCoreSpecification {

  @Shared
  Monitoring monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
  static mapper = new ObjectMapper(new MessagePackFactory())

  static newAgent(String latestVersion) {
    httpServer {
      handlers {
        put(latestVersion) {
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
    def client = createAgentApi(agent.address.toString())[1]
    def payload = prepareTraces(agentVersion, [])

    expect:
    def response = client.sendSerializedTraces(payload)
    response.success()
    response.status().present
    response.status().asInt == 200
    agent.getLastRequest().path == "/" + agentVersion

    cleanup:
    agent.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces", "v1/traces"]
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
    def client = createAgentApi(agent.address.toString())[1]
    Payload payload = prepareTraces("v0.3/traces", [])
    expect:
    def clientResponse = client.sendSerializedTraces(payload)
    !clientResponse.success()
    clientResponse.status().present
    clientResponse.status().asInt == 404
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
    def client = createAgentApi(agent.address.toString())[1]
    def payload = prepareTraces(agentVersion, traces)

    expect:
    client.sendSerializedTraces(payload).success()
    agent.lastRequest.contentType == "application/msgpack"
    agent.lastRequest.headers.get("Datadog-Client-Computed-Top-Level") == "true"
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "${traces.size()}"
    agent.lastRequest.headers.get("Datadog-Client-Dropped-P0-Traces") == "${payload.droppedTraces()}"
    agent.lastRequest.headers.get("Datadog-Client-Dropped-P0-Spans") == "${payload.droppedSpans()}"
    convertList(agentVersion, agent.lastRequest.body) == expectedRequestBody

    cleanup:
    agent.close()

    // Populate thread info dynamically as it is different when run via gradle vs idea.
    where:
    // spotless:off
    traces                                                                                                           | expectedRequestBody
    []                                                                                                               | []
    // service propagation enabled
    [[buildSpan(1L, "service.name", "my-service", PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"))]] | [[new TreeMap<>([
      "duration" : 10,
      "error"    : 0,
      "meta"     : ["thread.name": Thread.currentThread().getName(), "_dd.p.usr": "123", "_dd.p.dm": "-1"] +
        (Config.get().isExperimentalPropagateProcessTagsEnabled() ? ["_dd.tags.process" : ProcessTags.getTagsForSerialization().toString()] : []),
      "metrics"  : [
        (DDSpanContext.PRIORITY_SAMPLING_KEY)          : 1,
        (InstrumentationTags.DD_TOP_LEVEL as String)   : 1,
        (RateByServiceTraceSampler.SAMPLING_AGENT_RATE): 1.0,
        "thread.id"                                    : Thread.currentThread().id
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
    // service propagation disabled
    [[buildSpan(100L, "resource.name", "my-resource", PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"))]] | [[new TreeMap<>([
      "duration" : 10,
      "error"    : 0,
      "meta"     : ["thread.name": Thread.currentThread().getName(), "_dd.p.usr": "123", "_dd.p.dm": "-1"] +
        (Config.get().isExperimentalPropagateProcessTagsEnabled() ? ["_dd.tags.process" : ProcessTags.getTagsForSerialization().toString()] : []),
      "metrics"  : [
        (DDSpanContext.PRIORITY_SAMPLING_KEY)          : 1,
        (InstrumentationTags.DD_TOP_LEVEL as String)   : 1,
        (RateByServiceTraceSampler.SAMPLING_AGENT_RATE): 1.0,
        "thread.id"                                    : Thread.currentThread().id
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
    // spotless:on

    ignore = traces.each {
      it.each {
        it.finish()
        it.@durationNano = 10
      }
    }
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.4/traces"]
  }

  def "Api ResponseListeners see 200 responses"() {
    setup:
    def agentResponse = new AtomicReference<Map>(null)
    RemoteResponseListener responseListener = { String endpoint, Map responseJson ->
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
    def client = createAgentApi(agent.address.toString())[1]
    client.addResponseListener(responseListener)
    def payload = prepareTraces(agentVersion, [[], [], []])
    payload.withDroppedTraces(1)
    payload.withDroppedTraces(3)

    when:
    client.sendSerializedTraces(payload)
    then:
    agentResponse.get() == ["hello": [:]]
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "3" // false data shows the value provided via traceCounter.
    agent.lastRequest.headers.get("Datadog-Client-Dropped-P0-Traces") == "${payload.droppedTraces()}"
    agent.lastRequest.headers.get("Datadog-Client-Dropped-P0-Spans") == "${payload.droppedSpans()}"

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
    def client = createAgentApi(v3Agent.address.toString())[1]
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
    def client = createAgentApi("http://" + agent.address.host + ":" + port)[1]
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
    def client = createAgentApi(agent.address.toString())[1]
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
    // spotless:off
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
    // spotless:on
  }

  def "Embedded HTTP client rejects async requests"() {
    setup:
    def agent = newAgent("v0.5/traces")
    def (discovery, client) = createAgentApi(agent.address.toString())
    discovery.discover()
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

  void 'test metaStruct support on the encoded spans'() {
    setup:
    def agentVersion = 'v0.4/traces'
    def meta1 = 'Hello World!'
    def meta2 = [Hello: ' World!']
    def agent = httpServer {
      handlers {
        put(agentVersion) {
          response.send()
        }
      }
    }
    def client = createAgentApi(agent.address.toString())[1]
    def span = buildSpan(1L, "fakeType", [:])
    .setMetaStruct('meta_1', meta1)
    .setMetaStruct('meta_2', meta2)
    def payload = prepareTraces(agentVersion, [[span]])

    expect:
    client.sendSerializedTraces(payload).success()
    def body = convertList(agentVersion, agent.lastRequest.body)[0][0]
    def metaStruct = body['meta_struct'] as Map<String, byte[]>
    assert metaStruct.size() == 2
    assert mapper.readValue(metaStruct['meta_1'], String) == meta1
    assert mapper.readValue(metaStruct['meta_2'], Map) == meta2

    cleanup:
    agent.close()
  }

  static List<List<TreeMap<String, Object>>> convertList(String agentVersion, byte[] bytes) {
    if (agentVersion.equals("v0.5/traces")) {
      return convertListV5(bytes)
    }
    def returnVal = mapper.readValue(bytes, new TypeReference<List<List<TreeMap<String, Object>>>>() {})
    returnVal.each {
      it.each {
        it["meta"].remove("runtime-id")
        it["meta"].remove("language")
      }
    }

    return returnVal
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

          map.get("meta").remove("runtime-id")
          map.get("meta").remove("language")
        }
        mapTrace.add(map)
      }
      maps.add(mapTrace)
    }
    return maps
  }

  Payload prepareTraces(String agentVersion, List<List<DDSpan>> traces) {
    Traces traceCapture = new Traces()
    def packer = new MsgPackWriter(new FlushingBuffer(1 << 20, traceCapture))

    TraceMapper traceMapper
    switch (agentVersion) {
      case "v1/traces":
        traceMapper = new TraceMapperV1_0()
        break

      case "v0.5/traces":
        traceMapper = new TraceMapperV0_5()
        break

      default:
        traceMapper = new TraceMapperV0_4()
    }

    for (trace in traces) {
      packer.format(trace, traceMapper)
    }
    packer.flush()

    return traceMapper.newPayload()
      .withBody(traceCapture.traceCount,
      traces.isEmpty() ? ByteBuffer.allocate(0) : traceCapture.buffer)
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

  def createAgentApi(String url) {
    HttpUrl agentUrl = HttpUrl.get(url)
    OkHttpClient client = OkHttpUtils.buildHttpClient(agentUrl, 1000)
    DDAgentFeaturesDiscovery discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true, true)
    return [discovery, new DDAgentApi(client, agentUrl, discovery, monitoring, false)]
  }
}

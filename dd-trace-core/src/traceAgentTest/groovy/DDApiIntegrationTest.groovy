import static datadog.trace.api.ProtocolVersion.V0_4
import static datadog.trace.api.ProtocolVersion.V0_5
import static datadog.trace.api.ProtocolVersion.V1_0

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.OkHttpUtils
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.metrics.api.statsd.StatsDClient
import datadog.metrics.impl.MonitoringImpl
import datadog.trace.api.Config
import datadog.trace.api.ProtocolVersion
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.Payload
import datadog.trace.common.writer.RemoteApi
import datadog.trace.common.writer.RemoteResponseListener
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.common.writer.ddagent.TraceMapperV1
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import spock.lang.Shared

class DDApiIntegrationTest extends AbstractTraceAgentTest {
  def tracer
  DDSpan span

  // Looks like okHttp needs to resolve this, even for connection over socket
  static final SOMEHOST = "datadoghq.com"
  static final SOMEPORT = 123

  @Shared
  Process process
  @Shared
  File socketPath

  def discovery
  def udsDiscovery
  def api
  def unixDomainSocketApi
  TraceMapper mapper
  String traceEndpoint

  def endpoint = new AtomicReference<String>(null)
  def agentResponse = new AtomicReference<Map<String, Map<String, Number>>>(null)

  RemoteResponseListener responseListener = { String receivedEndpoint, Map<String, Map<String, Number>> responseJson ->
    endpoint.set(receivedEndpoint)
    agentResponse.set(responseJson)
  }

  def setupSpec() {
    File tmpDir = File.createTempDir()
    tmpDir.deleteOnExit()
    socketPath = new File(tmpDir, "socket")
    println "!!!socat UNIX-LISEN:${socketPath},reuseaddr,fork TCP-CONNECT:${agentContainerHost}:${agentContainerPort}"
    process = Runtime.getRuntime().exec("socat UNIX-LISTEN:${socketPath},reuseaddr,fork TCP-CONNECT:${agentContainerHost}:${agentContainerPort}")
  }

  def setup() {
    tracer = CoreTracer.builder().writer(new ListWriter()).build()
    span = tracer.buildSpan("fakeOperation").start()
    Thread.sleep(1)
    span.finish()
  }

  def cleanup() {
    tracer?.close()
  }

  def cleanupSpec() {
    process?.destroy()
  }

  def beforeTest(ProtocolVersion protocol) {
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
    HttpUrl agentUrl = HttpUrl.get(Config.get().getAgentUrl())
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(agentUrl, 5000)
    discovery = new DDAgentFeaturesDiscovery(httpClient, monitoring, agentUrl, protocol, true)
    api = new DDAgentApi(httpClient, agentUrl, discovery, monitoring, false)
    api.addResponseListener(responseListener)
    HttpUrl udsAgentUrl = HttpUrl.get(String.format("http://%s:%d", SOMEHOST, SOMEPORT))
    OkHttpClient udsClient = OkHttpUtils.buildHttpClient(true, socketPath.toString(), null, 5000)
    udsDiscovery = new DDAgentFeaturesDiscovery(udsClient, monitoring, agentUrl, protocol, true)
    unixDomainSocketApi = new DDAgentApi(udsClient, udsAgentUrl, udsDiscovery, monitoring, false)
    unixDomainSocketApi.addResponseListener(responseListener)
    mapper = [
      (V1_0): new TraceMapperV1(),
      (V0_5): new TraceMapperV0_5(),
    ].get(protocol, new TraceMapperV0_4())
    traceEndpoint = protocol.traceEndpoints().get(0)
  }

  def "Sending empty traces succeeds (test #test)"() {
    setup:
    beforeTest(protocol)
    expect:
    RemoteApi.Response response = api.sendSerializedTraces(prepareRequest(traces, mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert discovery.getTraceEndpoint() == traceEndpoint
    assert endpoint.get() == "${Config.get().getAgentUrl()}/${traceEndpoint}"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    // spotless:off
    traces                 | test | protocol
    []                     | 1    | V0_5
    (1..16).collect { [] } | 4    | V0_5
    []                     | 5    | V0_4
    (1..16).collect { [] } | 8    | V0_4
    // spotless:on
  }

  def "Sending traces succeeds"() {
    setup:
    beforeTest(protocol)
    expect:
    RemoteApi.Response response = api.sendSerializedTraces(prepareRequest([[span]], mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert discovery.getTraceEndpoint() == traceEndpoint
    assert endpoint.get() == "${Config.get().getAgentUrl()}/${traceEndpoint}"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    protocol << [V0_5, V0_4]
  }

  def "Sending empty traces to unix domain socket succeeds (test #test)"() {
    setup:
    beforeTest(protocol)
    expect:
    RemoteApi.Response response = unixDomainSocketApi.sendSerializedTraces(prepareRequest(traces, mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert udsDiscovery.getTraceEndpoint() == traceEndpoint
    assert endpoint.get() == "http://${SOMEHOST}:${SOMEPORT}/${traceEndpoint}"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    // spotless:off
    traces | test | protocol
    []     | 1    | V0_5
    []     | 3    | V0_4
    // spotless:on
  }

  def "Sending traces to unix domain socket succeeds (protocol #protocol)"() {
    setup:
    beforeTest(protocol)
    expect:
    RemoteApi.Response response = unixDomainSocketApi.sendSerializedTraces(prepareRequest([[span]], mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert udsDiscovery.getTraceEndpoint() == traceEndpoint
    assert endpoint.get() == "http://${SOMEHOST}:${SOMEPORT}/${traceEndpoint}"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    protocol << [V0_5, V0_4]
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

  Payload prepareRequest(List<List<DDSpan>> traces, TraceMapper traceMapper) {
    Traces traceCapture = new Traces()
    def packer = new MsgPackWriter(new FlushingBuffer(1 << 10, traceCapture))
    for (trace in traces) {
      packer.format(trace, traceMapper)
    }
    packer.flush()
    return traceMapper.newPayload()
      .withBody(traceCapture.traceCount, traceCapture.buffer)
  }
}

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.HttpUtils
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.http.client.HttpClient
import datadog.http.client.HttpUrl
import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.api.statsd.StatsDClient
import datadog.trace.api.Config
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.Payload
import datadog.trace.common.writer.RemoteApi
import datadog.trace.common.writer.RemoteResponseListener
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import spock.lang.Shared

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
  String version

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

  def beforeTest(boolean enableV05) {
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
    HttpUrl agentUrl = HttpUrl.parse(Config.get().getAgentUrl())
    HttpClient httpClient = HttpUtils.buildHttpClient(agentUrl, 5000)
    discovery = new DDAgentFeaturesDiscovery(httpClient, monitoring, agentUrl, enableV05, true)
    api = new DDAgentApi(httpClient, agentUrl, discovery, monitoring, false)
    api.addResponseListener(responseListener)
    HttpUrl udsAgentUrl = HttpUrl.parse(String.format("http://%s:%d", SOMEHOST, SOMEPORT))
    HttpClient udsClient = HttpUtils.buildHttpClient(true, socketPath.toString(), null, 5000)
    udsDiscovery = new DDAgentFeaturesDiscovery(udsClient, monitoring, agentUrl, enableV05, true)
    unixDomainSocketApi = new DDAgentApi(udsClient, udsAgentUrl, udsDiscovery, monitoring, false)
    unixDomainSocketApi.addResponseListener(responseListener)
    mapper = enableV05 ? new TraceMapperV0_5() : new TraceMapperV0_4()
    version = enableV05 ? "v0.5" : "v0.4"
  }

  def "Sending empty traces succeeds (test #test)"() {
    setup:
    beforeTest(enableV05)
    expect:
    RemoteApi.Response response = api.sendSerializedTraces(prepareRequest(traces, mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert discovery.getTraceEndpoint() == "${version}/traces"
    assert endpoint.get() == "${Config.get().getAgentUrl()}/${version}/traces"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    // spotless:off
    traces                 | test | enableV05
    []                     | 1    | true
    (1..16).collect { [] } | 4    | true
    []                     | 5    | false
    (1..16).collect { [] } | 8    | false
    // spotless:on
  }

  def "Sending traces succeeds"() {
    setup:
    beforeTest(enableV05)
    expect:
    RemoteApi.Response response = api.sendSerializedTraces(prepareRequest([[span]], mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert discovery.getTraceEndpoint() == "${version}/traces"
    assert endpoint.get() == "${Config.get().getAgentUrl()}/${version}/traces"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    enableV05 << [true, false]
  }

  def "Sending empty traces to unix domain socket succeeds (test #test)"() {
    setup:
    beforeTest(enableV05)
    expect:
    RemoteApi.Response response = unixDomainSocketApi.sendSerializedTraces(prepareRequest(traces, mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert udsDiscovery.getTraceEndpoint() == "${version}/traces"
    assert endpoint.get() == "http://${SOMEHOST}:${SOMEPORT}/${version}/traces"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    // spotless:off
    traces | test | enableV05
    []     | 1    | true
    []     | 3    | false
    // spotless:on
  }

  def "Sending traces to unix domain socket succeeds (enableV05 #enableV05)"() {
    setup:
    beforeTest(enableV05)
    expect:
    RemoteApi.Response response = unixDomainSocketApi.sendSerializedTraces(prepareRequest([[span]], mapper))
    assert !response.response().isEmpty()
    assert !response.exception().present
    assert response.status().present
    assert 200 == response.status().asInt
    assert response.success()
    assert udsDiscovery.getTraceEndpoint() == "${version}/traces"
    assert endpoint.get() == "http://${SOMEHOST}:${SOMEPORT}/${version}/traces"
    assert agentResponse.get()["rate_by_service"] instanceof Map

    where:
    enableV05 << [true, false]
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

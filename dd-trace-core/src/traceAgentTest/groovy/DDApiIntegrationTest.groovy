import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.OkHttpUtils
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.StatsDClient
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
import datadog.trace.core.monitor.MonitoringImpl
import datadog.trace.test.util.DDSpecification
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy
import spock.lang.Requires
import spock.lang.Shared

import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Do not run tests locally on Java7 since testcontainers are not compatible with Java7
// It is fine to run on CI because CI provides agent externally, not through testcontainers
@Requires({ "true" == System.getenv("CI") || jvm.java8Compatible })
class DDApiIntegrationTest extends DDSpecification {
  def tracer = CoreTracer.builder().writer(new ListWriter()).build()
  DDSpan span

  // Looks like okHttp needs to resolve this, even for connection over socket
  static final SOMEHOST = "datadoghq.com"
  static final SOMEPORT = 123

  /*
   Note: type here has to stay undefined, otherwise tests will fail in CI in Java 7 because
   'testcontainers' are built for Java 8 and Java 7 cannot load this class.
   */
  @Shared
  def agentContainer
  @Shared
  def agentContainerHost = "localhost"
  @Shared
  def agentContainerPort = 8126
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
    /*
     CI will provide us with agent container running along side our build.
     When building locally, however, we need to take matters into our own hands
     and we use 'testcontainers' for this.
     */
    if ("true" != System.getenv("CI")) {
      agentContainer = new GenericContainer("datadog/agent:7.22.0")
        .withEnv(["DD_APM_ENABLED": "true",
          "DD_BIND_HOST"  : "0.0.0.0",
          "DD_API_KEY"    : "invalid_key_but_this_is_fine",
          "DD_LOGS_STDOUT": "yes"])
        .withExposedPorts(datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT)
        .withStartupTimeout(Duration.ofSeconds(120))
        // Apparently we need to sleep for a bit so agent's response `{"service:,env:":1}` in rate_by_service.
        // This is clearly a race-condition and maybe we should avoid verifying complete response
        .withStartupCheckStrategy(new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10)))
      //        .withLogConsumer { output ->
      //        print output.utf8String
      //      }
      agentContainer.start()
      agentContainerHost = agentContainer.getHost()
      agentContainerPort = agentContainer.getMappedPort(datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT)
    }

    File tmpDir = File.createTempDir()
    tmpDir.deleteOnExit()
    socketPath = new File(tmpDir, "socket")
    println "!!!socat UNIX-LISEN:${socketPath},reuseaddr,fork TCP-CONNECT:${agentContainerHost}:${agentContainerPort}"
    process = Runtime.getRuntime().exec("socat UNIX-LISTEN:${socketPath},reuseaddr,fork TCP-CONNECT:${agentContainerHost}:${agentContainerPort}")
  }

  def setup() {
    span = tracer.buildSpan("fakeOperation").start()
    Thread.sleep(1)
    span.finish()
  }

  def cleanup() {
    tracer?.close()
  }

  def cleanupSpec() {
    if (agentContainer) {
      agentContainer.stop()
    }
    process.destroy()
  }

  def beforeTest(boolean enableV05) {
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
    HttpUrl agentUrl = HttpUrl.get(String.format("http://%s:%d", agentContainerHost, agentContainerPort))
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(agentUrl, 5000)
    discovery = new DDAgentFeaturesDiscovery(httpClient, monitoring, agentUrl, enableV05, true)
    api = new DDAgentApi(httpClient, agentUrl, discovery, monitoring, false)
    api.addResponseListener(responseListener)
    HttpUrl udsAgentUrl = HttpUrl.get(String.format("http://%s:%d", SOMEHOST, SOMEPORT))
    OkHttpClient udsClient = OkHttpUtils.buildHttpClient(udsAgentUrl, socketPath.toString(), null, 5000)
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
    assert null == response.exception()
    assert 200 == response.status()
    assert response.success()
    assert discovery.getTraceEndpoint() == "${version}/traces"
    assert endpoint.get() == "http://${agentContainerHost}:${agentContainerPort}/${version}/traces"
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
    assert null == response.exception()
    assert 200 == response.status()
    assert response.success()
    assert discovery.getTraceEndpoint() == "${version}/traces"
    assert endpoint.get() == "http://${agentContainerHost}:${agentContainerPort}/${version}/traces"
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
    assert null == response.exception()
    assert 200 == response.status()
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

  def "Sending traces to unix domain socket succeeds (test #test)"() {
    setup:
    beforeTest(enableV05)
    expect:
    RemoteApi.Response response = unixDomainSocketApi.sendSerializedTraces(prepareRequest([[span]], mapper))
    assert !response.response().isEmpty()
    assert null == response.exception()
    assert 200 == response.status()
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

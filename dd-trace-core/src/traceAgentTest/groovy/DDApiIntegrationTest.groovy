import com.timgroup.statsd.NonBlockingStatsDClient
import com.timgroup.statsd.StatsDClient
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.DDAgentResponseListener
import datadog.trace.common.writer.ddagent.Payload
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.monitor.Monitoring
import datadog.trace.core.serialization.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.MsgPackWriter
import datadog.trace.test.util.DDSpecification
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
  static final WRITER = new ListWriter()
  static final TRACER = CoreTracer.builder().writer(WRITER).build()
  static final CONTEXT = new DDSpanContext(
    DDId.from(1),
    DDId.from(1),
    DDId.ZERO,
    "fakeService",
    "fakeOperation",
    "fakeResource",
    PrioritySampling.UNSET,
    null,
    [:],
    false,
    "fakeType",
    0,
    TRACER.pendingTraceFactory.create(DDId.ONE),
    TRACER,
    [:])

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
  @Shared
  StatsDClient statsDClient

  def api
  def unixDomainSocketApi
  TraceMapper mapper
  String version

  def endpoint = new AtomicReference<String>(null)
  def agentResponse = new AtomicReference<Map<String, Map<String, Number>>>(null)

  DDAgentResponseListener responseListener = { String receivedEndpoint, Map<String, Map<String, Number>> responseJson ->
    endpoint.set(receivedEndpoint)
    agentResponse.set(responseJson)
  }

  def setupSpec() {
    statsDClient = new NonBlockingStatsDClient("itest", agentContainerHost, 8125)

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
      agentContainerHost = agentContainer.containerIpAddress
      agentContainerPort = agentContainer.getMappedPort(datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT)
    }

    File tmpDir = File.createTempDir()
    tmpDir.deleteOnExit()
    socketPath = new File(tmpDir, "socket")
    println "!!!socat UNIX-LISEN:${socketPath},reuseaddr,fork TCP-CONNECT:${agentContainerHost}:${agentContainerPort}"
    process = Runtime.getRuntime().exec("socat UNIX-LISTEN:${socketPath},reuseaddr,fork TCP-CONNECT:${agentContainerHost}:${agentContainerPort}")
  }

  def cleanupSpec() {
    if (agentContainer) {
      agentContainer.stop()
    }
    if (null != statsDClient) {
      statsDClient.close()
    }
    process.destroy()
  }

  def beforeTest(boolean enableV05) {
    Monitoring monitoring = new Monitoring(statsDClient, 1, TimeUnit.SECONDS)
    api = new DDAgentApi(String.format("http://%s:%d", agentContainerHost, agentContainerPort), null, 5000, enableV05, false, monitoring)
    api.addResponseListener(responseListener)
    mapper = api.selectTraceMapper()
    version = mapper instanceof TraceMapperV0_5 ? "v0.5" : "v0.4"
    unixDomainSocketApi = new DDAgentApi(String.format("http://%s:%d", SOMEHOST, SOMEPORT), socketPath.toString(), 5000, enableV05, false, monitoring)
    unixDomainSocketApi.addResponseListener(responseListener)
  }

  def "Sending traces succeeds (test #test)"() {
    setup:
    beforeTest(enableV05)
    expect:
    DDAgentApi.Response response = api.sendSerializedTraces(prepareRequest(traces, mapper))
    assert !response.response().isEmpty()
    assert null == response.exception()
    assert 200 == response.status()
    assert response.success()
    assert api.detectedVersion == "${version}/traces"
    assert endpoint.get() == "http://${agentContainerHost}:${agentContainerPort}/${version}/traces"
    assert agentResponse.get() == [rate_by_service: ["service:,env:": 1]]

    where:
    traces                                                                              | test | enableV05
    []                                                                                  | 1    | true
    [[new DDSpan(1, CONTEXT)]]                                                          | 2    | true
    [[new DDSpan(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()), CONTEXT)]] | 3    | true
    (1..16).collect { [] }                                                              | 4    | true
    []                                                                                  | 5    | false
    [[new DDSpan(1, CONTEXT)]]                                                          | 6    | false
    [[new DDSpan(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()), CONTEXT)]] | 7    | false
    (1..16).collect { [] }                                                              | 8    | false
  }

  def "Sending traces to unix domain socket succeeds (test #test)"() {
    setup:
    beforeTest(enableV05)
    expect:
    DDAgentApi.Response response = unixDomainSocketApi.sendSerializedTraces(prepareRequest(traces, mapper))
    assert !response.response().isEmpty()
    assert null == response.exception()
    assert 200 == response.status()
    assert response.success()
    assert api.detectedVersion == "${version}/traces"
    assert endpoint.get() == "http://${SOMEHOST}:${SOMEPORT}/${version}/traces"
    assert agentResponse.get() == [rate_by_service: ["service:,env:": 1]]

    where:
    traces                     | test | enableV05
    []                         | 1    | true
    [[new DDSpan(1, CONTEXT)]] | 2    | true
    []                         | 3    | false
    [[new DDSpan(1, CONTEXT)]] | 4    | false
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

  Payload prepareRequest(List<List<DDSpan>> traces, TraceMapper traceMapper) {
    ByteBuffer buffer = ByteBuffer.allocate(1 << 20)
    Traces traceCapture = new Traces()
    def packer = new MsgPackWriter(traceCapture, buffer)
    for (trace in traces) {
      packer.format(trace, traceMapper)
    }
    packer.flush()
    return traceMapper.newPayload()
      .withBody(traceCapture.traceCount, traceCapture.buffer)
      .withRepresentativeCount(traceCapture.representativeCount)
  }
}

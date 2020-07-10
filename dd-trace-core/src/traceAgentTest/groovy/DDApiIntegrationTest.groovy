import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.DDAgentResponseListener
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.core.CoreTracer
import datadog.trace.api.DDId
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.Packer
import datadog.trace.util.test.DDSpecification
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
    [:],
    new PendingTrace(TRACER, DDId.ONE),
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

  def api
  def unixDomainSocketApi

  def endpoint = new AtomicReference<String>(null)
  def agentResponse = new AtomicReference<Map<String, Map<String, Number>>>(null)

  DDAgentResponseListener responseListener = { String receivedEndpoint, Map<String, Map<String, Number>> responseJson ->
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
      agentContainer = new GenericContainer("datadog/docker-dd-agent:latest")
        .withEnv(["DD_APM_ENABLED": "true",
                  "DD_BIND_HOST"  : "0.0.0.0",
                  "DD_API_KEY"    : "invalid_key_but_this_is_fine",
                  "DD_LOGS_STDOUT": "yes"])
        .withExposedPorts(datadog.trace.api.Config.DEFAULT_TRACE_AGENT_PORT)
        .withStartupTimeout(Duration.ofSeconds(120))
      // Apparently we need to sleep for a bit so agent's response `{"service:,env:":1}` in rate_by_service.
      // This is clearly a race-condition and maybe we should avoid verifying complete response
        .withStartupCheckStrategy(new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10)))
      //        .withLogConsumer { output ->
      //        print output.utf8String
      //      }
      agentContainer.start()
      agentContainerHost = agentContainer.containerIpAddress
      agentContainerPort = agentContainer.getMappedPort(datadog.trace.api.Config.DEFAULT_TRACE_AGENT_PORT)
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
    process.destroy()
  }

  def setup() {
    api = new DDAgentApi(agentContainerHost, agentContainerPort, null, 5000)
    api.addResponseListener(responseListener)

    unixDomainSocketApi = new DDAgentApi(SOMEHOST, SOMEPORT, socketPath.toString(), 5000)
    unixDomainSocketApi.addResponseListener(responseListener)
  }

  def "Sending traces succeeds (test #test)"() {
    expect:
    api.sendSerializedTraces(request.traceCount, request.representativeCount, request.buffer)
    assert endpoint.get() == "http://${agentContainerHost}:${agentContainerPort}/v0.4/traces"
    assert agentResponse.get() == [rate_by_service: ["service:,env:": 1]]

    where:
    request                                                                                             | test
    prepareRequest([])                                                                                  | 1
    prepareRequest([[], []])                                                                            | 2
    prepareRequest([[new DDSpan(1, CONTEXT)]])                                                          | 3
    prepareRequest([[new DDSpan(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()), CONTEXT)]]) | 4
    prepareRequest((1..15).collect { [] })                                                              | 5
    prepareRequest((1..16).collect { [] })                                                              | 6
    // Larger traces take more than 1 second to send to the agent and get a timeout exception:
//      (1..((1 << 16) - 1)).collect { [] }                                                 | 7
//      (1..(1 << 16)).collect { [] }                                                       | 8
  }

  def "Sending traces to unix domain socket succeeds (test #test)"() {
    expect:
    unixDomainSocketApi.sendSerializedTraces(request.traceCount, request.representativeCount, request.buffer)
    assert endpoint.get() == "http://${SOMEHOST}:${SOMEPORT}/v0.4/traces"
    assert agentResponse.get() == [rate_by_service: ["service:,env:": 1]]

    where:
    request                                                                                             | test
    prepareRequest([])                                                                                  | 1
    prepareRequest([[], []])                                                                            | 2
    prepareRequest([[new DDSpan(1, CONTEXT)]])                                                          | 3
    prepareRequest([[new DDSpan(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()), CONTEXT)]]) | 4
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

  Traces prepareRequest(List<List<DDSpan>> traces) {
    ByteBuffer buffer = ByteBuffer.allocate(1 << 20)
    Traces tracesToSend = new Traces()
    def packer = new Packer(tracesToSend, buffer)
    def traceMapper = new TraceMapper()
    for (trace in traces) {
      packer.format(trace, traceMapper)
    }
    packer.flush()
    return tracesToSend
  }
}

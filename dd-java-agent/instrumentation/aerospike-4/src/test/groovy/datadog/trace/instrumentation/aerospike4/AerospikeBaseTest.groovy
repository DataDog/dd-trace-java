package datadog.trace.instrumentation.aerospike4

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.testcontainers.containers.GenericContainer
import spock.lang.Requires
import spock.lang.Shared

import static datadog.trace.agent.test.utils.PortUtils.waitForPortToOpen
import static java.util.concurrent.TimeUnit.SECONDS
import static org.testcontainers.containers.wait.strategy.Wait.forLogMessage

// Do not run tests on Java7 since testcontainers are not compatible with Java7
@Requires({ jvm.java8Compatible })
abstract class AerospikeBaseTest extends AgentTestRunner {

  @Shared
  def aerospike

  @Shared
  String aerospikeHost = "localhost"

  @Shared
  int aerospikePort = 3000

  def setup() throws Exception {
    aerospike = new GenericContainer('aerospike:5.5.0.9')
      .withExposedPorts(3000)
      .waitingFor(forLogMessage(".*heartbeat-received.*\\n", 1))

    aerospike.start()

    aerospikePort = aerospike.getMappedPort(3000)

    waitForPortToOpen(aerospikePort, 10, SECONDS)
  }

  def cleanup() throws Exception {
    if (aerospike) {
      aerospike.stop()
    }
  }

  def aerospikeSpan(TraceAssert trace, int index, String methodName, Object parentSpan = null) {
    trace.span {
      serviceName "aerospike"
      operationName "aerospike.query"
      resourceName methodName
      spanType DDSpanTypes.AEROSPIKE
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      tags {
        "$Tags.COMPONENT" "java-aerospike"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" aerospikeHost
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" aerospikePort
        "$Tags.DB_TYPE" "aerospike"
        "$Tags.DB_INSTANCE" "test"
        defaultTags()
      }
    }
  }
}

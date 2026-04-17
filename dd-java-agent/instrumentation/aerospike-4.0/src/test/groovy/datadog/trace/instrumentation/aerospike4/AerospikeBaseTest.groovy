package datadog.trace.instrumentation.aerospike4

import static datadog.environment.OperatingSystem.isArm64
import static datadog.environment.OperatingSystem.isLinux
import static datadog.trace.agent.test.utils.PortUtils.waitForPortToOpen
import static java.util.concurrent.TimeUnit.SECONDS
import static org.junit.jupiter.api.Assumptions.assumeFalse
import static org.testcontainers.containers.wait.strategy.Wait.forLogMessage

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared

abstract class AerospikeBaseTest extends VersionedNamingTestBase {

  @Shared
  def aerospike

  @Shared
  String aerospikeHost = "localhost"

  @Shared
  int aerospikePort = 3000

  def setup() throws Exception {
    // TODO: skip aerospike under arm46 Linux, as supported since ce-6.2.0.2 only
    assumeFalse(isArm64() && isLinux())

    aerospike = new GenericContainer('aerospike:5.5.0.9')
      .withExposedPorts(3000)
      .waitingFor(forLogMessage(".*heartbeat-received.*\\n", 1))

    aerospike.start()

    aerospikePort = aerospike.getMappedPort(3000)

    waitForPortToOpen(aerospikePort, 10, SECONDS)
  }

  def cleanup() throws Exception {
    aerospike?.stop()
  }

  def aerospikeSpan(TraceAssert trace, int index, String methodName, Object parentSpan = null) {
    trace.span {
      serviceName service()
      operationName operation()
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
        peerServiceFrom(Tags.DB_INSTANCE)
        defaultTags()
      }
    }
  }
}

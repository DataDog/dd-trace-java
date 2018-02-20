import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.jaxrs.OpenTracingFilter
import org.jboss.resteasy.plugins.server.netty.NettyContainer
import org.jboss.resteasy.spi.ResteasyDeployment
import org.jboss.resteasy.test.TestPortProvider

class ResteasyNettyTest extends AgentTestRunner {

  static {
    System.setProperty("dd.integration.jax-rs.enabled", "true")
  }

  def "test resource"() {
    setup:
    def port = TestPortProvider.port
    def deployment = new ResteasyDeployment()
    deployment.setActualResourceClasses(Collections.singletonList(TestResource))
    deployment.setActualProviderClasses(Collections.singletonList(OpenTracingFilter))
    NettyContainer.start(deployment)

    // Make the connection without using Resteasy's client builder to preserve Java 7 support
    def url = new URL("http://localhost:${port}/test/hello/bob")
    def connection = url.openConnection()
    def response = null
    connection.with {
      doOutput = true
      requestMethod = 'POST'
      response = content.text
    }

    expect:
    response == "Hello bob!"
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1

    def trace = TEST_WRITER.firstTrace()
    def span = trace[0]
    span.resourceName == "POST /test/hello/{name}"
    span.tags["component"] == "jax-rs"

    cleanup:
    NettyContainer.stop()
  }
}

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.jaxrs.OpenTracingFilter
import org.jboss.resteasy.plugins.server.netty.NettyContainer
import org.jboss.resteasy.spi.ResteasyDeployment
import org.jboss.resteasy.test.TestPortProvider

import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity

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

    def invocation = ClientBuilder.newClient()
      .target("http://localhost:${port}/test/hello/bob")
      .request()
      .accept("application/json")
      .buildPost(Entity.entity("junk", "text/plain"))
    def response = invocation.invoke().readEntity(String)

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

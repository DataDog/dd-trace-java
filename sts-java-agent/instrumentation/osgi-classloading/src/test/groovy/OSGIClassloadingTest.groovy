import stackstate.trace.agent.test.AgentTestRunner

class OSGIClassloadingTest extends AgentTestRunner {
  def "delegation property set on module load"() {
    setup:
    org.osgi.framework.Bundle.getName()

    expect:
    System.getProperty("org.osgi.framework.bootdelegation") == "io.opentracing.*,io.opentracing,stackstate.slf4j.*,stackstate.slf4j,stackstate.trace.bootstrap.*,stackstate.trace.bootstrap,stackstate.trace.api.*,stackstate.trace.api,stackstate.trace.context.*,stackstate.trace.context"
  }
}

import stackstate.trace.agent.test.AgentTestRunner
import spock.lang.Timeout

@Timeout(5)
class JBossClassloadingTest extends AgentTestRunner {
  def "delegation property set on module load"() {
    setup:
    org.jboss.modules.Module.getName()

    expect:
    System.getProperty("jboss.modules.system.pkgs") == "io.opentracing,stackstate.slf4j,stackstate.trace.bootstrap,stackstate.trace.api,stackstate.trace.context"
  }
}

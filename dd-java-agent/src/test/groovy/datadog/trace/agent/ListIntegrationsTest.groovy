package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import datadog.trace.bootstrap.AgentJar
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class ListIntegrationsTest extends Specification {

  def "dd.trace.debug false"() {
    setup:
    def testOutput = new ByteArrayOutputStream()
    expect:
    IntegrationTestUtils.runOnSeparateJvm(AgentJar.name
      , []
      , ["-li"]
      , [:]
      , new PrintStream(testOutput)) == 0
    !new String(testOutput.toByteArray()).contains("ERROR")
  }
}

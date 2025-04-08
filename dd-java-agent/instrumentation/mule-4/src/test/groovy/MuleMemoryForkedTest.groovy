import datadog.trace.agent.test.AgentTestRunner

class MuleMemoryForkedTest extends AgentTestRunner {

  def "Forked memory should be high"() {
    when:
    def max = Runtime.getRuntime().maxMemory()

    then:
    max == 768 * 1024 * 1024
  }
}

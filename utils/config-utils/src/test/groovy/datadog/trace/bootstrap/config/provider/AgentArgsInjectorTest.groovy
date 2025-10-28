package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification

class AgentArgsInjectorTest extends DDSpecification {

  def "injects agent arguments as system properties"() {
    given:
    def agentArgs = "arg1=value1,arg2=value2"

    when:
    AgentArgsInjector.injectAgentArgsConfig(agentArgs)

    then:
    System.getProperty("arg1") == "value1"
    System.getProperty("arg2") == "value2"
  }
}

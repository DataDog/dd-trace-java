package datadog.trace.bootstrap

import spock.lang.Specification

class AgentBootstrapTest extends Specification {
  def 'return true when first exception in the cause chain is the specified exception'() {
    setup:
    def ex = new IOException()

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    causeChainContainsException
  }

  def 'return false when exception cause chain does not contain specified exception'() {
    setup:
    def ex = new NullPointerException()

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    !causeChainContainsException
  }

  def 'return true when exception cause chain contains specified exception as a cause'() {
    setup:
    def ex = new Exception(new IOException())

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    causeChainContainsException
  }

  def 'return false when exception cause chain has a cycle'() {
    setup:
    def ex = Mock(Exception)
    ex.getCause() >> ex

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    !causeChainContainsException
  }
}

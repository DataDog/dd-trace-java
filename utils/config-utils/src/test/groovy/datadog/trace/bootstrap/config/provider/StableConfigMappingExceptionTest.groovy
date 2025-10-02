package datadog.trace.bootstrap.config.provider

import datadog.trace.bootstrap.config.provider.stableconfig.StableConfigMappingException
import spock.lang.Specification

class StableConfigMappingExceptionTest extends Specification {

  def "constructors work as expected"() {
    when:
    def ex1 = new StableConfigMappingException("msg")
    def ex2 = new StableConfigMappingException("msg2")

    then:
    ex1.message == "msg"
    ex1.cause == null
    ex2.message == "msg2"
  }

  def "safeToString handles null"() {
    expect:
    StableConfigMappingException.safeToString(null) == "null"
  }

  def "safeToString handles short string"() {
    expect:
    StableConfigMappingException.safeToString("short string") == "short string"
  }

  def "safeToString handles long string"() {
    given:
    def longStr = "a" * 101
    expect:
    StableConfigMappingException.safeToString(longStr) == ("a" * 50) + "...(truncated)..." + ("a" * 51).substring(1)
  }
}

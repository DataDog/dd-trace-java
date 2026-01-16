package datadog.trace.bootstrap.config.provider


import spock.lang.Specification

class AgentArgsParserTest extends Specification {

  def "parses a single argument"() {
    given:
    def args = "key1=value1"

    when:
    def properties = AgentArgsParser.parseAgentArgs(args)

    then:
    properties != null
    properties.size() == 1
    properties.get("key1") == "value1"
  }

  def "parses multiple arguments"() {
    given:
    def args = "key1=value1,key2=value2"

    when:
    def properties = AgentArgsParser.parseAgentArgs(args)

    then:
    properties != null
    properties.size() == 2
    properties.get("key2") == "value2"
  }

  def "returns null for null string"() {
    given:
    def args = null

    when:
    def properties = AgentArgsParser.parseAgentArgs(args)

    then:
    properties == null
  }

  def "returns null for empty string"() {
    given:
    def args = ""

    when:
    def properties = AgentArgsParser.parseAgentArgs(args)

    then:
    properties == null
  }

  def "returns null for malformed string"() {
    given:
    def args = "key=value,,,=="

    when:
    def properties = AgentArgsParser.parseAgentArgs(args)

    then:
    properties == null
  }

  def "parses argument with spaces"() {
    given:
    def args = "key=value with spaces"

    when:
    def properties = AgentArgsParser.parseAgentArgs(args)

    then:
    properties != null
    properties.size() == 1
    properties.get("key") == "value with spaces"
  }
}

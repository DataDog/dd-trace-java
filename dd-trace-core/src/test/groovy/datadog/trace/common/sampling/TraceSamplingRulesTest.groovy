package datadog.trace.common.sampling

import datadog.trace.core.test.DDCoreSpecification

class TraceSamplingRulesTest extends DDCoreSpecification {

  def "Deserialize empty list of Trace Sampling rules from JSON"() {
    when:
    def rules = TraceSamplingRules.deserialize("[]")

    then:
    rules.rules.size() == 0
  }

  def "Deserialize Trace Sampling rules from JSON"() {
    when:
    def rules = TraceSamplingRules.deserialize("""[
      {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": 0.0}, 
      {\"service\": \"service-name\", \"sample_rate\": 0.5}, 
      {\"name\": \"operation-name\", \"sample_rate\": 0.75}, 
      {\"resource\": \"resource-name\", \"sample_rate\": 1},
      {\"sample_rate\": 0.25}
    ]""")

    then:
    rules.rules.size() == 5

    rules.rules[0].service == "usersvc"
    rules.rules[0].name == "healthcheck"
    rules.rules[0].resource == null    
    rules.rules[0].sampleRate == 0.0d

    rules.rules[1].service == "service-name"
    rules.rules[1].name == null
    rules.rules[1].resource == null
    rules.rules[1].sampleRate == 0.5d

    rules.rules[2].service == null
    rules.rules[2].name == "operation-name"
    rules.rules[2].resource == null
    rules.rules[2].sampleRate == 0.75d

    rules.rules[3].service == null
    rules.rules[3].name == null
    rules.rules[3].resource == "resource-name"
    rules.rules[3].sampleRate == 1d
    
    rules.rules[4].service == null
    rules.rules[4].name == null
    rules.rules[4].resource == null
    rules.rules[4].sampleRate == 0.25d
  }

  def "Skip Trace Sampling Rules with invalid rate values"() {
    when:
    def rules = TraceSamplingRules.deserialize("""[
      {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": $rate}
    ]""")

    then:
    rules.rules.size() == 0

    where:
    rate << ["-0.1", "-11", "1.2", "100", null, "\"zero\"", "\"\""]
  }

  def "Skip Trace Sampling Rules when incorrect JSON provided"() {
    when:
    def rules = TraceSamplingRules.deserialize(jsonRules)

    then:
    rules == null

    where:
    jsonRules << ["[", "{\\\"service\\\": \\\"usersvc\\\",}", ""]
  }
}

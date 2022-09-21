package datadog.trace.common.sampling

import datadog.trace.core.test.DDCoreSpecification

class SamplingRuleJsonTest extends DDCoreSpecification {

  def "Deserialize empty list of sampling rules from JSON"() {
    when:
    def rules = JsonSamplingRules.deserialize("[]")
    then:
    rules.rules.size == 0
  }

  def "Deserialize a sampling rule from JSON"() {
    when:
    def rules = JsonSamplingRules.deserialize("[{\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": 0.0}, {\"service\": \"usersvc\", \"sample_rate\": 0.5}]")
    then:
    rules.rules.size() == 2
    rules.rules[0].service == "usersvc"
    rules.rules[0].name == "healthcheck"
    rules.rules[0].sample_rate == 0.0
    rules.rules[1].service == "usersvc"
    rules.rules[1].name == null
    rules.rules[1].sample_rate == 0.5
  }
}

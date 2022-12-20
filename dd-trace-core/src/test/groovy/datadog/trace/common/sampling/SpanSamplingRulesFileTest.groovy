package datadog.trace.common.sampling

import datadog.trace.core.test.DDCoreSpecification

import java.nio.file.Files
import java.nio.file.Path

class SpanSamplingRulesFileTest extends DDCoreSpecification {

  static createRulesFile(String rules) {
    Path p = Files.createTempFile('single-span-sampling-rules', '.json')
    p.toFile() << rules
    return p.toString()
  }

  def "Deserialize empty list of Span Sampling rules from JSON"() {
    when:
    def rules = SpanSamplingRules.deserializeFile(createRulesFile('[]'))

    then:
    rules == null
  }

  def "Deserialize Span Sampling rules from JSON"() {
    when:
    def rules = SpanSamplingRules.deserializeFile(createRulesFile("""[
      {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": 0.0}, 
      {\"service\": \"service-name\", \"sample_rate\": 0.5}, 
      {\"name\": \"operation-name\", \"sample_rate\": 0.75, \"max_per_second\": 10}, 
      {\"sample_rate\": 0.25}
    ]"""))

    then:
    rules.rules.size() == 4

    rules.rules[0].service == "usersvc"
    rules.rules[0].name == "healthcheck"
    rules.rules[0].sampleRate == 0.0d
    rules.rules[0].maxPerSecond == Integer.MAX_VALUE

    rules.rules[1].service == "service-name"
    rules.rules[1].name == null
    rules.rules[1].sampleRate == 0.5d
    rules.rules[1].maxPerSecond == Integer.MAX_VALUE

    rules.rules[2].service == null
    rules.rules[2].name == "operation-name"
    rules.rules[2].sampleRate == 0.75d
    rules.rules[2].maxPerSecond == 10

    rules.rules[3].service == null
    rules.rules[3].name == null
    rules.rules[3].sampleRate == 0.25d
    rules.rules[3].maxPerSecond == Integer.MAX_VALUE
  }

  def "Skip Span Sampling Rules with invalid sample_rate values"() {
    when:
    def rules = SpanSamplingRules.deserializeFile(createRulesFile("""[
      {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": $rate}
    ]"""))

    then:
    rules == null

    where:
    rate << ["-0.1", "-11", "1.2", "100", "\"zero\"", "\"\""]
  }

  def "Skip Span Sampling Rules with invalid max_per_second values"() {
    when:
    def rules = SpanSamplingRules.deserializeFile(createRulesFile("""[
      {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"max_per_second\": $limit}
    ]"""))

    then:
    rules == null

    where:
    limit << ["0", "-11", "\"zero\"", "\"\""]
  }

  def "Skip Span Sampling Rules when incorrect JSON provided"() {
    when:
    def rules = SpanSamplingRules.deserializeFile(createRulesFile(jsonRules))

    then:
    rules == null

    where:
    jsonRules << ["[", "{\\\"service\\\": \\\"usersvc\\\",}", ""]
  }
}

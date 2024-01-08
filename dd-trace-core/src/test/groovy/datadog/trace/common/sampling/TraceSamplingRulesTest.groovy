package datadog.trace.common.sampling

import com.squareup.moshi.Moshi
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.SamplingRule.MATCH_ALL

class TraceSamplingRulesTest extends DDCoreSpecification {

  def "Deserialize empty list of Trace Sampling Rules from JSON"() {
    when:
    def rules = TraceSamplingRules.deserialize("[]")

    then:
    rules.empty
  }

  def "Deserialize Trace Sampling Rules from JSON"() {
    when:
    def rules = TraceSamplingRules.deserialize("""[
      {"service": "service-name", "name": "operation-name", "resource": "resource-name", "tags":
        {"tag-name1": "tag-pattern1", 
         "tag-name2": "tag-pattern2"},
        "sample_rate": 0.0},
      {},
      {"service": "", "name": "", "resource": "", "tags": {}}, 
      {"service": null, "name": null, "resource": null, "tags": null, "sample_rate": null}, 
      
      {"sample_rate": 0.25},
      {"sample_rate": 0.5}, 
      {"sample_rate": 0.75},
      {"sample_rate": 1}
    ]""").rules
    def ruleIndex = 0

    then:
    rules.size() == 8

    // Test a complete rule
    rules[ruleIndex].service == "service-name"
    rules[ruleIndex].name == "operation-name"
    rules[ruleIndex].resource == "resource-name"
    rules[ruleIndex].tags == ["tag-name1": "tag-pattern1", "tag-name2": "tag-pattern2"]
    rules[ruleIndex++].sampleRate == 0d

    // Test default values with an empty rule
    rules[ruleIndex].service == MATCH_ALL
    rules[ruleIndex].name == MATCH_ALL
    rules[ruleIndex].resource == MATCH_ALL
    rules[ruleIndex].tags == [:]
    rules[ruleIndex++].sampleRate == 1d

    // Test rule with empty values
    rules[ruleIndex].service == ""
    rules[ruleIndex].name == ""
    rules[ruleIndex].resource == ""
    rules[ruleIndex].tags == [:]
    rules[ruleIndex++].sampleRate == 1d

    // Test rule with null values
    rules[ruleIndex].service == MATCH_ALL
    rules[ruleIndex].name == MATCH_ALL
    rules[ruleIndex].resource == MATCH_ALL
    rules[ruleIndex].tags == [:]
    rules[ruleIndex++].sampleRate == 1d

    // Test different sample rate values
    rules[ruleIndex++].sampleRate == 0.25d
    rules[ruleIndex++].sampleRate == 0.5d
    rules[ruleIndex++].sampleRate == 0.75d
    rules[ruleIndex++].sampleRate == 1d
  }

  def "Skip Trace Sampling Rules with invalid sample rate values: #rate"() {
    when:
    def rules = TraceSamplingRules.deserialize("""[
      {"service": "usersvc", "name": "healthcheck", "sample_rate": $rate}
    ]""")

    then:
    rules.empty

    where:
    rate << ['-0.1', '-11', '1.2', '100', '"zero"', '""', '{}', '[]']
  }

  def "Skip Trace Sampling Rules when incorrect JSON provided"() {
    when:
    def rules = TraceSamplingRules.deserialize(jsonRules)

    then:
    rules.empty

    where:
    jsonRules << ['[', '{"service": "usersvc",}', '']
  }

  def "Render JsonRule correctly when toString() is called"() {
    when:
    def jsonRule = new Moshi.Builder().build().adapter(TraceSamplingRules.JsonRule).fromJson(json)

    then:
    jsonRule.toString() == json

    where:
    json = '{"name":"name","resource":"resource","sample_rate":"0.5","service":"service","tags":{"a":"b","foo":"bar"}}'
  }

  def "Keep only valid rules when invalid rules are present"() {
    when:
    def rules = TraceSamplingRules.deserialize("""[
      {"service": "usersvc", "name": "healthcheck", "sample_rate": 0.5},
      {"service": "usersvc", "name": "healthcheck", "sample_rate": 200}
    ]""")

    then:
    rules.rules.size() == 1
  }
}

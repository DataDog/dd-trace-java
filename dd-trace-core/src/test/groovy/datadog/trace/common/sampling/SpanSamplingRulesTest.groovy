package datadog.trace.common.sampling

import com.squareup.moshi.Moshi
import datadog.trace.core.test.DDCoreSpecification

import java.nio.file.Files
import java.nio.file.Path

import static datadog.trace.api.sampling.SamplingRule.MATCH_ALL

class SpanSamplingRulesTest extends DDCoreSpecification {

  def deserializeRules(String jsonRules) {
    return SpanSamplingRules.deserialize(jsonRules)
  }

  def "Deserialize empty list of Span Sampling Rules from JSON"() {
    when:
    def rules = deserializeRules('[]')

    then:
    rules.empty
  }

  def "Deserialize Span Sampling Rules from JSON"() {
    when:
    def rules = deserializeRules("""[
      {"service": "service-name", "name": "operation-name", "resource": "resource-name", "tags":
        {"tag-name1": "tag-pattern1",
         "tag-name2": "tag-pattern2"},
        "sample_rate": 0.0, "max_per_second": 10.0},
      {},
      {"service": "", "name": "", "resource": "", "tags": {}},
      {"service": null, "name": null, "resource": null, "tags": null, "sample_rate": null, "sample_rate": null, "max_per_second": null},

      {"sample_rate": 0.25},
      {"sample_rate": 0.5},
      {"sample_rate": 0.75},
      {"sample_rate": 1},

      {"max_per_second": 0.2},
      {"max_per_second": 1.0},
      {"max_per_second": 10},
      {"max_per_second": 10.123},
      {"max_per_second": 10000}
    ]""").rules
    def ruleIndex = 0

    then:
    rules.size() == 13

    // Test a complete rule
    rules[ruleIndex].service == "service-name"
    rules[ruleIndex].name == "operation-name"
    rules[ruleIndex].resource == "resource-name"
    rules[ruleIndex].tags == ["tag-name1": "tag-pattern1", "tag-name2": "tag-pattern2"]
    rules[ruleIndex].sampleRate == 0.0d
    rules[ruleIndex++].maxPerSecond == 10

    // Test default values with an empty rule
    rules[ruleIndex].service == MATCH_ALL
    rules[ruleIndex].name == MATCH_ALL
    rules[ruleIndex].resource == MATCH_ALL
    rules[ruleIndex].tags == [:]
    rules[ruleIndex].sampleRate == 1d
    rules[ruleIndex++].maxPerSecond == Integer.MAX_VALUE

    // Test rule with empty values
    rules[ruleIndex].service == ""
    rules[ruleIndex].name == ""
    rules[ruleIndex].resource == ""
    rules[ruleIndex].tags == [:]
    rules[ruleIndex].sampleRate == 1d
    rules[ruleIndex++].maxPerSecond == Integer.MAX_VALUE

    // Test rule with null values
    rules[ruleIndex].service == MATCH_ALL
    rules[ruleIndex].name == MATCH_ALL
    rules[ruleIndex].resource == MATCH_ALL
    rules[ruleIndex].tags == [:]
    rules[ruleIndex].sampleRate == 1d
    rules[ruleIndex++].maxPerSecond == Integer.MAX_VALUE

    // Test different sample rate values
    rules[ruleIndex++].sampleRate == 0.25d
    rules[ruleIndex++].sampleRate == 0.5d
    rules[ruleIndex++].sampleRate == 0.75d
    rules[ruleIndex++].sampleRate == 1d

    // Test different max per second values
    rules[ruleIndex++].maxPerSecond == 1
    rules[ruleIndex++].maxPerSecond == 1
    rules[ruleIndex++].maxPerSecond == 10
    rules[ruleIndex++].maxPerSecond == 10
    rules[ruleIndex++].maxPerSecond == 10000
  }

  def "Skip Span Sampling Rules with invalid sample_rate values"() {
    when:
    def rules = deserializeRules("""[
      {"service": "usersvc", "name": "healthcheck", "sample_rate": $rate}
    ]""")

    then:
    rules.empty

    where:
    rate << ['-0.1', '-11', '1.2', '100', '"zero"', '""']
  }

  def "Skip Span Sampling Rules with invalid max_per_second values"() {
    when:
    def rules = deserializeRules("""[
      {"service": "usersvc", "name": "healthcheck", "max_per_second": $limit}
    ]""")

    then:
    rules.empty

    where:
    limit << ['0', '-11', '"zero"', '""']
  }

  def "Skip Span Sampling Rules when incorrect JSON provided"() {
    when:
    def rules = deserializeRules(jsonRules)

    then:
    rules.empty

    where:
    jsonRules << ['[', '{"service": "usersvc",}', '']
  }

  def "Render JsonRule correctly when toString() is called"() {
    when:
    def jsonRule = new Moshi.Builder().build().adapter(SpanSamplingRules.JsonRule).fromJson(json)

    then:
    jsonRule.toString() == json

    where:
    json = '{"max_per_second":"10","name":"name","resource":"resource","sample_rate":"0.5","service":"service","tags":{"a":"b","foo":"bar"}}'
  }

  def "Keep only valid rules when invalid rules are present"() {
    when:
    def rules = SpanSamplingRules.deserialize("""[
      {"service": "usersvc", "name": "healthcheck", "sample_rate": 0.5},
      {"service": "usersvc", "name": "healthcheck2", "sample_rate": 200}
    ]""")

    then:
    rules.rules.size() == 1
  }
}

class SpanSamplingRulesFileTest extends SpanSamplingRulesTest {
  static createRulesFile(String rules) {
    Path p = Files.createTempFile('single-span-sampling-rules', '.json')
    p.toFile() << rules
    return p.toString()
  }

  @Override
  def deserializeRules(String jsonRules) {
    return SpanSamplingRules.deserializeFile(createRulesFile(jsonRules))
  }
}

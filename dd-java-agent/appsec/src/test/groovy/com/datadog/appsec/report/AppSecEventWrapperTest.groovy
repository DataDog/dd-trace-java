package com.datadog.appsec.report

import com.datadog.appsec.ddwaf.WAFResultData.Rule
import com.datadog.appsec.ddwaf.WAFResultData.RuleMatch
import com.datadog.appsec.ddwaf.WAFResultData.Parameter
import datadog.trace.test.util.DDSpecification

class AppSecEventWrapperTest extends DDSpecification {

  void 'validate json serialization for AppSecEvent report without security_response_id'() {
    setup:
    def event = new AppSecEvent.Builder()
      .withRule(
      new Rule(
      id: 'rule_id',
      name: 'rule_name',
      tags: [tag: 'value'])
      )
      .withRuleMatches([
        new RuleMatch(
        operator: 'rule_match_operator',
        operator_value: 'rule_match_operator_value',
        parameters: [
          new Parameter(
          address: 'parameter_address',
          highlight: ['parameter_highlight'],
          key_path: ['parameter_key_path'],
          value: 'parameter_value')
        ])
      ])
      .build()

    def expectedJson = '{"triggers":[{"rule":{"id":"rule_id","name":"rule_name","tags":{"tag":"value"}},"rule_matches":[{"operator":"rule_match_operator","operator_value":"rule_match_operator_value","parameters":[{"address":"parameter_address","highlight":["parameter_highlight"],"key_path":["parameter_key_path"],"value":"parameter_value"}]}]}]}'

    when:
    def wrapper = new AppSecEventWrapper([event])
    def json = wrapper.toString()

    then:
    json == expectedJson
  }

  void 'validate json serialization for AppSecEvent report with security_response_id'() {
    setup:
    def event = new AppSecEvent.Builder()
      .withRule(
      new Rule(
      id: 'rule_id',
      name: 'rule_name',
      tags: [tag: 'value'])
      )
      .withRuleMatches([
        new RuleMatch(
        operator: 'rule_match_operator',
        operator_value: 'rule_match_operator_value',
        parameters: [
          new Parameter(
          address: 'parameter_address',
          highlight: ['parameter_highlight'],
          key_path: ['parameter_key_path'],
          value: 'parameter_value')
        ])
      ])
      .withSecurityResponseId('blk-123-456')
      .build()

    def expectedJson = '{"triggers":[{"rule":{"id":"rule_id","name":"rule_name","tags":{"tag":"value"}},"rule_matches":[{"operator":"rule_match_operator","operator_value":"rule_match_operator_value","parameters":[{"address":"parameter_address","highlight":["parameter_highlight"],"key_path":["parameter_key_path"],"value":"parameter_value"}]}],"security_response_id":"blk-123-456"}]}'

    when:
    def wrapper = new AppSecEventWrapper([event])
    def json = wrapper.toString()

    then:
    json == expectedJson
  }

  void 'validate json serialization excludes null security_response_id'() {
    setup:
    def event = new AppSecEvent.Builder()
      .withRule(
      new Rule(
      id: 'rule_id',
      name: 'rule_name',
      tags: [tag: 'value'])
      )
      .withRuleMatches([
        new RuleMatch(
        operator: 'rule_match_operator',
        operator_value: 'rule_match_operator_value',
        parameters: [
          new Parameter(
          address: 'parameter_address',
          highlight: ['parameter_highlight'],
          key_path: ['parameter_key_path'],
          value: 'parameter_value')
        ])
      ])
      .withSecurityResponseId(null)
      .build()

    def expectedJson = '{"triggers":[{"rule":{"id":"rule_id","name":"rule_name","tags":{"tag":"value"}},"rule_matches":[{"operator":"rule_match_operator","operator_value":"rule_match_operator_value","parameters":[{"address":"parameter_address","highlight":["parameter_highlight"],"key_path":["parameter_key_path"],"value":"parameter_value"}]}]}]}'

    when:
    def wrapper = new AppSecEventWrapper([event])
    def json = wrapper.toString()

    then:
    json == expectedJson
  }
}

package com.datadog.appsec.report

import datadog.trace.test.util.DDSpecification

class AppSecEventWrapperTest extends DDSpecification {

  void 'validate json serialization for AppSecEvent report'() {
    setup:
    def event = new AppSecEvent.Builder()
      .withRule(
      new Rule.Builder()
      .withId('rule_id')
      .withName('rule_name')
      .withTags([tag: 'value'])
      .build()
      )
      .withRuleMatches(
      [
        new RuleMatch.Builder()
        .withOperator('rule_match_operator')
        .withOperatorValue("rule_match_operator_value")
        .withParameters([
          new Parameter.Builder()
          .withAddress("parameter_address")
          .withHighlight(['parameter_highlight'])
          .withKeyPath(['parameter_key_path'])
          .withValue('parameter_value')
          .build()
        ]
        )
        .build()
      ]
      )
      .build()

    def expectedJson = '{"triggers":[{"rule":{"id":"rule_id","name":"rule_name","tags":{"tag":"value"}},"rule_matches":[{"operator":"rule_match_operator","operator_value":"rule_match_operator_value","parameters":[{"address":"parameter_address","highlight":["parameter_highlight"],"key_path":["parameter_key_path"],"value":"parameter_value"}]}]}]}'

    when:
    def wrapper = new AppSecEventWrapper([event])
    def json = wrapper.toString()

    then:
    json == expectedJson
  }
}

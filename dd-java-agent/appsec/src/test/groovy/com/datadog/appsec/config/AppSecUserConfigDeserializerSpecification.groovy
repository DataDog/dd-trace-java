package com.datadog.appsec.config

import groovy.json.JsonOutput
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class AppSecUserConfigDeserializerSpecification extends Specification {
  void 'rule toggling'() {
    expect:
    def res = AppSecUserConfigDeserializer.INSTANCE.deserialize(JsonOutput.toJson(
      rules_override: [[
          id: 'my id',
          enabled: false
        ]]
      ).getBytes(StandardCharsets.UTF_8)).build('cfg key')
    res.ruleToggling == [
      'my id': Boolean.FALSE
    ]
  }

  void 'if no overrides are specified discard'() {
    expect:
    def res = AppSecUserConfigDeserializer.INSTANCE.deserialize(JsonOutput.toJson(
      rules_override: [[ id: 'my id', ]]
      ).getBytes(StandardCharsets.UTF_8)).build('cfg key')
    res.ruleToggling == [:]
    res.ruleOverrides == [:]
  }

  void 'non toggling overrides'() {
    expect:
    def res = AppSecUserConfigDeserializer.INSTANCE.deserialize(JsonOutput.toJson(
      rules_override: [[ id: 'my_id', on_match: ['block'], discarded_key: true]]
      ).getBytes(StandardCharsets.UTF_8)).build('cfg key')
    res.ruleToggling == [:]
    res.ruleOverrides == [my_id: [on_match: ['block']]]
  }

  void 'mixing toggling and nontoggling overrides'() {
    expect:
    def res = AppSecUserConfigDeserializer.INSTANCE.deserialize(JsonOutput.toJson(
      rules_override: [[ id: 'my_id', enabled: false, on_match: ['block'], discarded_key: true]]
      ).getBytes(StandardCharsets.UTF_8)).build('cfg key')
    res.ruleToggling == [my_id: Boolean.FALSE]
    res.ruleOverrides == [my_id: [on_match: ['block']]]
  }

  void 'actions exclusions and custom rules are included verbatim'() {
    expect:
    def res = AppSecUserConfigDeserializer.INSTANCE.deserialize(JsonOutput.toJson(
      actions: [[a:1]],
      exclusions: [[b:2]],
      custom_rules: [[c:3]]
      ).getBytes(StandardCharsets.UTF_8)).build('cfg key')
    res.ruleToggling == [:]
    res.ruleOverrides == [:]
    res.actions == [[a:1]]
    res.exclusions == [[b:2]]
    res.customRules == [[c:3]]
  }
}

package com.datadog.appsec.config

import groovy.json.JsonOutput
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class AppSecRuleTogglingDeserializerSpecification extends Specification {
  void 'transforms list of maps to map id to boolean'() {
    expect:
    def res = AppSecRuleTogglingDeserializer.INSTANCE.deserialize(JsonOutput.toJson(
      rules_override: [[
          id: 'my id',
          enabled: false
        ]]
      ).getBytes(StandardCharsets.UTF_8))
    res == [
      'my id': Boolean.FALSE
    ]
  }

  void 'if enabled is omitted assume false'() {
    expect:
    def res = AppSecRuleTogglingDeserializer.INSTANCE.deserialize(JsonOutput.toJson(
      rules_override: [[ id: 'my id', ]]
      ).getBytes(StandardCharsets.UTF_8))
    res == [
      'my id': Boolean.FALSE
    ]
  }

  void 'if there is no data assume we want to enable all the rules'() {
    expect:
    def res = AppSecRuleTogglingDeserializer.INSTANCE.deserialize(
      "{}".getBytes(StandardCharsets.UTF_8))
    res == [:]
  }
}

package com.datadog.appsec.config

import spock.lang.Specification

class AppSecUserConfigDeserializerSpecification extends Specification {

  void 'all the components'() {
    when:
    def res = AppSecUserConfigDeserializer.INSTANCE.deserialize('''
      {
        "rules_override": [{"a": 0}],
        "custom_rules": [{"b": 1}],
        "exclusions": [{"c": 2}],
        "actions": [{"d": 3}]
      }'''.bytes).build('cfg key')

    then:
    res.ruleOverrides == [[a: 0]]
    res.customRules == [[b: 1]]
    res.exclusions == [[c: 2]]
    res.actions == [[d: 3]]
  }

  void 'none of the components'() {
    when:
    def res = AppSecUserConfigDeserializer.INSTANCE.deserialize('{}'.bytes).build('cfg key')

    then:
    res.ruleOverrides == []
    res.customRules == []
    res.exclusions == []
    res.actions == []
    res.configKey == 'cfg key'
  }
}

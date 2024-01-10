package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.ModuleExecutionSettings
import datadog.trace.api.civisibility.config.TestIdentifier
import spock.lang.Specification

class ModuleExecutionSettingsSerializerTest extends Specification {

  def "test serialization: #settings"() {
    when:
    def serialized = ModuleExecutionSettingsSerializer.serialize(settings)
    def deserialized = ModuleExecutionSettingsSerializer.deserialize(serialized)

    then:
    deserialized == settings

    where:
    settings << [
      new ModuleExecutionSettings(false, false, false, [:], [:], [], []),
      new ModuleExecutionSettings(true, false, true, ["a": "b", "propName" : "propValue"], ["module" : [new TestIdentifier("a", "bc", "def", null), new TestIdentifier("abc", "de", "f", null)]], [new TestIdentifier("suite", "name", null, null)], ["a", "bcde", "f", "ghhi"]),
      new ModuleExecutionSettings(false, true, false, ["a": "b", "propName" : "propValue"], [:], [new TestIdentifier("suite", "name", null, null), new TestIdentifier("a", "b", "c", null)], ["a", "bcde", "f", "ghhi"]),
      new ModuleExecutionSettings(true, true, true, ["a": "b", "propName" : "propValue", "anotherProp" : "value"], ["module" : [new TestIdentifier("a", "bc", "def", null), new TestIdentifier("abc", "de", "f", null)], "module-b": [new TestIdentifier("suite", "name", null, null)], "module-c": []], [], [])
    ]
  }
}

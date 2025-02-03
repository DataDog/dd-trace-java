package datadog.trace.civisibility.config

import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

class TestManagementSettingsSerializerTest extends Specification {
  def "test TestManagementSettings serialization: #iterationIndex"() {
    when:
    Serializer s = new Serializer()
    TestManagementSettingsSerializer.serialize(s, settings)

    def buffer = s.flush()
    def deserialized = TestManagementSettingsSerializer.deserialize(buffer)

    then:
    deserialized == settings

    where:
    settings << [
      TestManagementSettings.DEFAULT,
      new TestManagementSettings(true, 10),
    ]
  }
}

package datadog.trace.civisibility.config

import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

class TestManagementSettingsTest extends Specification {
  def "test serialization: #iterationIndex"() {
    when:
    Serializer s = new Serializer()
    TestManagementSettings.Serializer.serialize(s, settings)

    def buffer = s.flush()
    def deserialized = TestManagementSettings.Serializer.deserialize(buffer)

    then:
    deserialized == settings

    where:
    settings << [TestManagementSettings.DEFAULT, new TestManagementSettings(true, 10),]
  }
}

package datadog.trace.civisibility.config


import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

class TestSettingsTest extends Specification {

  def "test serialization: #tests"() {
    given:
    when:
    Serializer s = new Serializer()
    s.write(tests, TestSetting.TestSettingsSerializer::serialize)
    def serializedTests = s.flush()
    def deserializedTests = Serializer.readList(serializedTests, TestSetting.TestSettingsSerializer::deserialize)

    then:
    deserializedTests == tests

    where:
    tests << [
      // empty
      [],
      [TestSetting.FLAKY],
      [TestSetting.KNOWN],
      [TestSetting.QUARANTINED],
      [TestSetting.DISABLED],
      [TestSetting.ATTEMPT_TO_FIX],
      [TestSetting.ATTEMPT_TO_FIX, TestSetting.QUARANTINED],
    ]
  }
}

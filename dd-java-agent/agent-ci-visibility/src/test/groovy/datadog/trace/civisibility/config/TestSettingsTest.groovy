package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

class TestSettingsTest extends Specification {

  def "test serialization: #tests"() {
    given:
    when:
    Serializer s = new Serializer()
    s.write(tests, TestSettings.TestSettingsSerializer::serialize)
    def serializedTests = s.flush()
    def deserializedTests = Serializer.readList(serializedTests, TestSettings.TestSettingsSerializer::deserialize)

    then:
    deserializedTests == tests

    where:
    tests << [
      // empty
      [],
      [TestSettings.FLAKY],
      [TestSettings.KNOWN],
      [TestSettings.QUARANTINED],
      [TestSettings.DISABLED],
      [TestSettings.ATTEMPT_TO_FIX],
      [TestSettings.ATTEMPT_TO_FIX, TestSettings.QUARANTINED],
    ]
  }
}

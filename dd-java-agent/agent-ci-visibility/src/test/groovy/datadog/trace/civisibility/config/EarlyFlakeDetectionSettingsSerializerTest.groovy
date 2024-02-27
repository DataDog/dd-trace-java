package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.EarlyFlakeDetectionSettings
import datadog.trace.civisibility.ipc.Serializer
import spock.lang.Specification

class EarlyFlakeDetectionSettingsSerializerTest extends Specification {

  def "test serialization: #settings"() {
    when:
    Serializer s = new Serializer()
    EarlyFlakeDetectionSettingsSerializer.serialize(s, settings)

    def buffer = s.flush()
    def deserialized = EarlyFlakeDetectionSettingsSerializer.deserialize(buffer)

    then:
    deserialized == settings

    where:
    settings << [
      new EarlyFlakeDetectionSettings(false, Collections.emptyList(), -1),
      new EarlyFlakeDetectionSettings(true, [], 10),
      new EarlyFlakeDetectionSettings(true, [new EarlyFlakeDetectionSettings.ExecutionsByDuration(0, 0)], 10),
      new EarlyFlakeDetectionSettings(true, [new EarlyFlakeDetectionSettings.ExecutionsByDuration(1, 2)], 20),
      new EarlyFlakeDetectionSettings(true, [
        new EarlyFlakeDetectionSettings.ExecutionsByDuration(1, 2),
        new EarlyFlakeDetectionSettings.ExecutionsByDuration(3, 4)
      ], 30),
    ]
  }
}

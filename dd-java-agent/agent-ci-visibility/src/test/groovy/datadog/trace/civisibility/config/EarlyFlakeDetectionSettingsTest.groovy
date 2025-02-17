package datadog.trace.civisibility.config


import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

class EarlyFlakeDetectionSettingsTest extends Specification {

  def "test serialization: #iterationIndex"() {
    when:
    Serializer s = new Serializer()
    EarlyFlakeDetectionSettings.Serializer.serialize(s, settings)

    def buffer = s.flush()
    def deserialized = EarlyFlakeDetectionSettings.Serializer.deserialize(buffer)

    then:
    deserialized == settings

    where:
    settings << [
      new EarlyFlakeDetectionSettings(false, Collections.emptyList(), -1),
      new EarlyFlakeDetectionSettings(true, [], 10),
      new EarlyFlakeDetectionSettings(true, [new ExecutionsByDuration(0, 0)], 10),
      new EarlyFlakeDetectionSettings(true, [new ExecutionsByDuration(1, 2)], 20),
      new EarlyFlakeDetectionSettings(true, [new ExecutionsByDuration(1, 2), new ExecutionsByDuration(3, 4)], 30),
    ]
  }
}

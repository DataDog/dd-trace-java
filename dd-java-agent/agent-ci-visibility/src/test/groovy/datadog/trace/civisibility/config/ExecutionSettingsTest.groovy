package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.EarlyFlakeDetectionSettings
import datadog.trace.api.civisibility.config.TestIdentifier
import spock.lang.Specification

class ExecutionSettingsTest extends Specification {

  def "test serialization: #settings"() {
    when:
    def serialized = ExecutionSettings.ExecutionSettingsSerializer.serialize(settings)
    def deserialized = ExecutionSettings.ExecutionSettingsSerializer.deserialize(serialized)

    then:
    deserialized == settings

    where:
    settings << [
      new ExecutionSettings(false, false, false, false, EarlyFlakeDetectionSettings.DEFAULT, null, [:], [:], null, [:]),
      new ExecutionSettings(true, true, false, true,
      new EarlyFlakeDetectionSettings(true, [], 10),
      "",
      ["module": new HashSet<>([new TestIdentifier("a", "bc", "def", null), new TestIdentifier("abc", "de", "f", null)])], [:],
      [new TestIdentifier("suite", "name", null, null)],
      ["bundle": [new TestIdentifier("a", "b", "c", null)]]),
      new ExecutionSettings(false, false, true, false,
      new EarlyFlakeDetectionSettings(true, [new EarlyFlakeDetectionSettings.ExecutionsByDuration(10, 20)], 10),
      "itrCorrelationId",
      [:],
      ["cov"    : BitSet.valueOf(new byte[]{
          1, 2, 3
        }), "cov2": BitSet.valueOf(new byte[]{
          4, 5, 6
        })],
      [new TestIdentifier("suite", "name", null, null), new TestIdentifier("a", "b", "c", null)],
      ["bundle": [new TestIdentifier("a", "b", "c", null), new TestIdentifier("aa", "bb", "cc", null)]]),
      new ExecutionSettings(true, true, true, true,
      new EarlyFlakeDetectionSettings(true, [
        new EarlyFlakeDetectionSettings.ExecutionsByDuration(10, 20),
        new EarlyFlakeDetectionSettings.ExecutionsByDuration(30, 40)
      ], 10),
      "itrCorrelationId",
      ["module": new HashSet<>([new TestIdentifier("a", "bc", "def", null), new TestIdentifier("abc", "de", "f", null)]), "module-b": new HashSet<>([new TestIdentifier("suite", "name", null, null)]), "module-c": new HashSet<>([])],
      ["cov"    : BitSet.valueOf(new byte[]{
          1, 2, 3
        }), "cov2": BitSet.valueOf(new byte[]{
          4, 5, 6
        })],
      [],
      ["bundle": [new TestIdentifier("a", "b", "c", null)], "bundle-2": [new TestIdentifier("aa", "bb", "cc", null)]])
    ]
  }
}

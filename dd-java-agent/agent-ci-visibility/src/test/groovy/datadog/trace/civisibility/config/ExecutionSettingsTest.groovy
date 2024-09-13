package datadog.trace.civisibility.config


import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.config.TestMetadata
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
      new ExecutionSettings(
      false,
      false,
      false,
      false,
      EarlyFlakeDetectionSettings.DEFAULT,
      null,
      [:],
      [:],
      null,
      new HashSet<>([])),

      new ExecutionSettings(
      true,
      true,
      false,
      true,
      new EarlyFlakeDetectionSettings(true, [], 10),
      "",
      [new TestIdentifier("bc", "def", "g"): new TestMetadata(true), new TestIdentifier("de", "f", null): new TestMetadata(false)],
      [:],
      new HashSet<>([new TestIdentifier("name", null, null)]),
      new HashSet<>([new TestIdentifier("b", "c", "g")])
      ),

      new ExecutionSettings(
      false,
      false,
      true,
      false,
      new EarlyFlakeDetectionSettings(true, [new EarlyFlakeDetectionSettings.ExecutionsByDuration(10, 20)], 10),
      "itrCorrelationId",
      [:],
      ["cov": BitSet.valueOf(new byte[]{
          1, 2, 3
        }), "cov2": BitSet.valueOf(new byte[]{
          4, 5, 6
        })],
      new HashSet<>([new TestIdentifier("name", null, "g"), new TestIdentifier("b", "c", null)]),
      new HashSet<>([new TestIdentifier("b", "c", null), new TestIdentifier("bb", "cc", null)]),
      ),

      new ExecutionSettings(
      true,
      true,
      true,
      true,
      new EarlyFlakeDetectionSettings(true, [new EarlyFlakeDetectionSettings.ExecutionsByDuration(10, 20), new EarlyFlakeDetectionSettings.ExecutionsByDuration(30, 40)], 10),
      "itrCorrelationId",
      [new TestIdentifier("bc", "def", null): new TestMetadata(true), new TestIdentifier("de", "f", null): new TestMetadata(true)],
      ["cov": BitSet.valueOf(new byte[]{
          1, 2, 3
        }), "cov2": BitSet.valueOf(new byte[]{
          4, 5, 6
        })],
      new HashSet<>([]),
      new HashSet<>([new TestIdentifier("b", "c", null), new TestIdentifier("bb", "cc", "g")]),
      ),
    ]
  }
}

package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.config.TestMetadata
import datadog.trace.civisibility.diff.LineDiff
import spock.lang.Specification

import static datadog.trace.civisibility.TestUtils.lines

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
      false,
      EarlyFlakeDetectionSettings.DEFAULT,
      TestManagementSettings.DEFAULT,
      null,
      [:],
      [:],
      null,
      new HashSet<>([]),
      new HashSet<>([]),
      new HashSet<>([]),
      new HashSet<>([]),
      LineDiff.EMPTY),

      new ExecutionSettings(
      true,
      true,
      false,
      true,
      true,
      new EarlyFlakeDetectionSettings(true, [], 10),
      new TestManagementSettings(true, 20),
      "",
      [(new TestIdentifier("bc", "def", "g")): new TestMetadata(true), (new TestIdentifier("de", "f", null)): new TestMetadata(false)],
      [:],
      new HashSet<>([new TestFQN("name", null)]),
      new HashSet<>([new TestFQN("b", "c")]),
      new HashSet<>([new TestFQN("suite", "quarantined")]),
      new HashSet<>([new TestFQN("suite", "disabled")]),
      new HashSet<>([new TestFQN("suite", "attemptToFix")]),
      new LineDiff(["path": lines()])
      ),

      new ExecutionSettings(
      false,
      false,
      true,
      false,
      true,
      new EarlyFlakeDetectionSettings(true, [new EarlyFlakeDetectionSettings.ExecutionsByDuration(10, 20)], 10),
      new TestManagementSettings(true, 20),
      "itrCorrelationId",
      [:],
      ["cov"    : BitSet.valueOf(new byte[]{
          1, 2, 3
        }), "cov2": BitSet.valueOf(new byte[]{
          4, 5, 6
        })],
      new HashSet<>([new TestFQN("name", null), new TestFQN("b", "c")]),
      new HashSet<>([new TestFQN("b", "c"), new TestFQN("bb", "cc")]),
      new HashSet<>([new TestFQN("suite", "quarantined"), new TestFQN("another", "another-quarantined")]),
      new HashSet<>([new TestFQN("suite", "disabled"), new TestFQN("another", "another-disabled")]),
      new HashSet<>([new TestFQN("suite", "attemptToFix"), new TestFQN("another", "another-attemptToFix")]),
      new LineDiff(["path": lines(1, 2, 3)]),
      ),

      new ExecutionSettings(
      true,
      true,
      true,
      true,
      true,
      new EarlyFlakeDetectionSettings(true, [new EarlyFlakeDetectionSettings.ExecutionsByDuration(10, 20), new EarlyFlakeDetectionSettings.ExecutionsByDuration(30, 40)], 10),
      new TestManagementSettings(true, 20),
      "itrCorrelationId",
      [(new TestIdentifier("bc", "def", null)): new TestMetadata(true), (new TestIdentifier("de", "f", null)): new TestMetadata(true)],
      ["cov"    : BitSet.valueOf(new byte[]{
          1, 2, 3
        }), "cov2": BitSet.valueOf(new byte[]{
          4, 5, 6
        })],
      new HashSet<>([]),
      new HashSet<>([new TestFQN("b", "c"), new TestFQN("bb", "cc")]),
      new HashSet<>([new TestFQN("suite", "quarantined"), new TestFQN("another", "another-quarantined")]),
      new HashSet<>([new TestFQN("suite", "disabled"), new TestFQN("another", "another-disabled")]),
      new HashSet<>([new TestFQN("suite", "attemptToFix"), new TestFQN("another", "another-attemptToFix")]),
      new LineDiff(["path": lines(1, 2, 3), "path-b": lines(1, 2, 128, 257, 999)]),
      ),
    ]
  }
}

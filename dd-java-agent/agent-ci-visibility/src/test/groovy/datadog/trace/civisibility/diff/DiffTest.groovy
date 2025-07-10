package datadog.trace.civisibility.diff

import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

import static datadog.trace.civisibility.TestUtils.lines

class DiffTest extends Specification {

  def "test diffs serialization: #diff"() {
    when:
    def s = new Serializer()
    Diff.SERIALIZER.serialize(diff, s)
    def buffer = s.flush()
    def diffCopy = Diff.SERIALIZER.deserialize(buffer)

    then:
    diff == diffCopy

    where:
    diff << [
      new LineDiff([:]),
      new LineDiff(["path": lines(10)]),
      new LineDiff(["path": lines(10, 11, 13)]),
      new LineDiff(["path": lines(10, 11, 12, 13), "path-b": lines()]),
      new LineDiff(["path": lines(10, 11, 12, 13), "path-b": lines(8, 18)])
    ]
  }
}

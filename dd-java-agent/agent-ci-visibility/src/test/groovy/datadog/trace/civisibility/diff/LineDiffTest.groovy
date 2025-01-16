package datadog.trace.civisibility.diff


import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

import static datadog.trace.civisibility.TestUtils.lines

class LineDiffTest extends Specification {

  def "test diff contains line interval"() {
    when:
    def diff = new LineDiff(lines)

    then:
    diff.contains(path, interval[0], interval[1]) == result

    where:
    lines                                               | path     | interval  | result
    ["path": lines(10)]                                 | "path-b" | [0, 9999] | false
    ["path": lines(10)]                                 | "path-b" | [10, 10]  | false
    ["path": lines(10)]                                 | "path"   | [0, 9999] | true
    ["path": lines(10)]                                 | "path"   | [10, 10]  | true
    ["path": lines(10)]                                 | "path"   | [9, 9]    | false
    ["path": lines(10)]                                 | "path"   | [11, 11]  | false
    ["path": lines(10)]                                 | "path"   | [9, 11]   | true
    ["path": lines(10, 11, 13)]                         | "path"   | [9, 11]   | true
    ["path": lines(10, 11, 13)]                         | "path"   | [12, 12]  | false
    ["path": lines(10, 11, 13)]                         | "path"   | [12, 14]  | true
    ["path": lines(10, 11, 13)]                         | "path"   | [9, 14]   | true
    ["path": lines(10, 11, 12, 13)]                     | "path"   | [9, 11]   | true
    ["path": lines(10, 11, 12, 13)]                     | "path"   | [9, 14]   | true
    ["path": lines(10, 11, 12, 13)]                     | "path"   | [11, 12]  | true
    ["path": lines(10, 11, 12, 13)]                     | "path"   | [12, 14]  | true
    ["path": lines(10, 11, 12, 13), "path-b": lines()]  | "path-b" | [0, 9999] | false
    ["path": lines(10, 11, 12, 13), "path-b": lines(8)] | "path-b" | [0, 9999] | true
    ["path": lines(10, 11, 12, 13), "path-b": lines(8)] | "path-b" | [7, 8]    | true
  }

  def "test serialization: #lines"() {
    given:

    def diff = new LineDiff(lines)

    when:
    def serializer = new Serializer()
    diff.serialize(serializer)
    def buf = serializer.flush()

    then:
    LineDiff.deserialize(buf) == diff

    where:
    lines << [
      [:],
      ["path": lines(10)],
      ["path": lines(10, 11, 13)],
      ["path": lines(10, 11, 12, 13), "path-b": lines()],
      ["path": lines(10, 11, 12, 13), "path-b": lines(8, 18)]
    ]
  }
}

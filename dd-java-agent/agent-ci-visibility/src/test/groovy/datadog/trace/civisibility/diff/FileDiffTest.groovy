package datadog.trace.civisibility.diff

import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

class FileDiffTest extends Specification {

  def "test diff contains file"() {
    when:
    def diff = new FileDiff(new HashSet<>(paths))

    then:
    diff.contains(path, 0, Integer.MAX_VALUE) == result

    where:
    paths                | path     | result
    ["path-a"]           | "path-a" | true
    ["path-a"]           | "path-b" | false
    ["path-a", "path-b"] | "path-a" | true
    ["path-a", "path-b"] | "path-b" | true
    ["path-a", "path-b"] | "path-c" | false
  }

  def "test serialization: #paths"() {
    given:
    def diff = new FileDiff(new HashSet<>(paths))

    when:
    def serializer = new Serializer()
    diff.serialize(serializer)
    def buf = serializer.flush()

    then:
    FileDiff.deserialize(buf) == diff

    where:
    paths << [[], ["path-a"], ["path-a", "path-b"]]
  }
}

package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.SkippableTest
import spock.lang.Specification

import java.util.stream.Collectors

class SkippableTestsSerializerTest extends Specification {

  def "test serialization: #tests"() {
    given:
    def testsList = tests.stream().map { t -> new SkippableTest(t[0], t[1], t[2], null) }.collect(Collectors.toList())

    when:
    def serializedTests = SkippableTestsSerializer.serialize(testsList)
    def deserializedTests = SkippableTestsSerializer.deserialize(serializedTests)

    then:
    deserializedTests == testsList

    where:
    tests << [
      // single test
      [["suite", "name", null]],
      [["suite", "name", "parameters"]],
      [["suite", "name", "{\"metadata\":{\"test_name\":\"test display name with #a #b #c\"}}"]],
      // multiple tests
      [["suite", "name", "parameters"], ["a", "b", "c"]],
      [["suite", "name", null], ["a", "b", "c"]],
      [["suite", "name", null], ["a", "b", null]],
      [["suite", "name", "parameters"], ["a", "b", null]],
    ]
  }
}

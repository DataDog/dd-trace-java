package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.TestIdentifier
import spock.lang.Specification

import java.util.stream.Collectors

class TestIdentifierSerializerTest extends Specification {

  def "test serialization: #tests"() {
    given:
    def testsList = tests.stream().map { t -> new TestIdentifier(t[0], t[1], t[2], null) }.collect(Collectors.toList())

    when:
    def serializedTests = TestIdentifierSerializer.serialize(testsList)
    def deserializedTests = TestIdentifierSerializer.deserialize(serializedTests)

    then:
    deserializedTests == testsList

    where:
    tests << [
      // empty
      [],
      // single test
      [["suite", "name", null]],
      [["suite", "𝕄 add user properties 𝕎 addUserProperties()", null]],
      // non-ASCII characters
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

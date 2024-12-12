package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.ipc.Serializer
import spock.lang.Specification

class TestIdentifierSerializerTest extends Specification {

  def "test serialization: #tests"() {
    given:
    when:
    Serializer s = new Serializer()
    s.write(tests, TestIdentifierSerializer::serialize)
    def serializedTests = s.flush()
    def deserializedTests = Serializer.readList(serializedTests, TestIdentifierSerializer::deserialize)

    then:
    deserializedTests == tests

    where:
    tests << [
      // empty
      [],
      // single test
      [new TestIdentifier("suite", "name", null)],
      [new TestIdentifier("suite", "𝕄 add user properties 𝕎 addUserProperties()", null)],
      [new TestIdentifier("suite", "name", null)],
      [new TestIdentifier("suite", "name", "parameters")],
      [new TestIdentifier("suite", "name", "{\"metadata\":{\"test_name\":\"test display name with #a #b #c\"}}")],
      // multiple tests
      [new TestIdentifier("suite", "name", "parameters"), new TestIdentifier("a", "b", "c")],
      [new TestIdentifier("suite", "name", null), new TestIdentifier("a", "b", "c")],
      [new TestIdentifier("suite", "name", null), new TestIdentifier("a", "b", null)],
      [new TestIdentifier("suite", "name", "parameters"), new TestIdentifier("a", "b", null)],
    ]
  }
}

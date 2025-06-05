package datadog.trace.civisibility.config

import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.civisibility.ipc.serialization.Serializer
import spock.lang.Specification

class TestFQNSerializerTest extends Specification {

  def "test serialization: #tests"() {
    given:
    when:
    Serializer s = new Serializer()
    s.write(tests, TestFQNSerializer::serialize)
    def serializedTests = s.flush()
    def deserializedTests = Serializer.readList(serializedTests, TestFQNSerializer::deserialize)

    then:
    deserializedTests == tests

    where:
    tests << [
      // empty
      [],
      // single test
      [new TestFQN("suite", "name")],
      [new TestFQN("suite", "𝕄 add user properties 𝕎 addUserProperties()")],
      [new TestFQN("suite", "name")],
      [new TestFQN("suite", "name")],
      [new TestFQN("suite", "name")],
      // multiple tests
      [new TestFQN("suite", "name"), new TestFQN("a", "b")],
      [new TestFQN("suite", "name"), new TestFQN("a", "b")],
      [new TestFQN("suite", "name"), new TestFQN("a", "b")],
      [new TestFQN("suite", "name"), new TestFQN("a", "b")],
    ]
  }
}

package datadog.trace.civisibility.ipc

import spock.lang.Specification

import java.nio.ByteBuffer

class ModuleExecutionResultTest extends Specification {

  def "test serialization and deserialization: #signal"() {
    when:
    def bytes = signal.serialize()
    def deserialized = ModuleExecutionResult.deserialize(bytes)

    then:
    deserialized == signal

    where:
    signal << [
      new ModuleExecutionResult(12345, 67890, false, false, 0, null, null),
      new ModuleExecutionResult(12345, 67890, true, false, 1, "abc", "def"),
      new ModuleExecutionResult(12345, 67890, false, true, 2, null, "def"),
      new ModuleExecutionResult(12345, 67890, false, false, 3, "abc", null),
      new ModuleExecutionResult(12345, 67890, true, true, Integer.MAX_VALUE, "abc", "def")
    ]
  }

  def "throws exception when deserializing array of incorrect length"() {
    given:
    def bytes = new byte[2]

    when:
    ModuleExecutionResult.deserialize(ByteBuffer.wrap(bytes))

    then:
    thrown IllegalArgumentException
  }
}

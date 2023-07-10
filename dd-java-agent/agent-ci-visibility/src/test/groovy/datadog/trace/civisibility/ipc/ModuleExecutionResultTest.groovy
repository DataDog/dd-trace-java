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
      new ModuleExecutionResult(12345, 67890, false, false, false),
      new ModuleExecutionResult(12345, 67890, true, false, false),
      new ModuleExecutionResult(12345, 67890, false, true, false),
      new ModuleExecutionResult(12345, 67890, false, false, true),
      new ModuleExecutionResult(12345, 67890, true, true, true)
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

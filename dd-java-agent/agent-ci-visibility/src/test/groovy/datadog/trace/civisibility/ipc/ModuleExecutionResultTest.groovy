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
      new ModuleExecutionResult(12345, 67890, false, false, 0, Collections.emptyList(), null),
      new ModuleExecutionResult(12345, 67890, true, false, 1, Collections.singletonList(new TestFramework("junit", "4.13.2")), new byte[] {
        1, 2, 3
      }),
      new ModuleExecutionResult(12345, 67890, false, true, 2, Arrays.asList(new TestFramework("junit", "4.13.2"), new TestFramework("junit", "5.9.2")), new byte[] {
        1, 2, 3
      }),
      new ModuleExecutionResult(12345, 67890, false, false, 3, Arrays.asList(new TestFramework("junit", null), new TestFramework("junit", "5.9.2")), new byte[] {
        1, 2, 3
      }),
      new ModuleExecutionResult(12345, 67890, true, true, Integer.MAX_VALUE, Arrays.asList(new TestFramework("junit", "4.13.2"), new TestFramework(null, "5.9.2")), new byte[] {
        1, 2, 3
      })
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

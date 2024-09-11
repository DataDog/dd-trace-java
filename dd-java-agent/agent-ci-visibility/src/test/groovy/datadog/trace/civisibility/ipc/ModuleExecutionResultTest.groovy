package datadog.trace.civisibility.ipc

import spock.lang.Specification

class ModuleExecutionResultTest extends Specification {

  def "test serialization and deserialization: #signal"() {
    when:
    def bytes = signal.serialize()
    def deserialized = ModuleExecutionResult.deserialize(bytes)

    then:
    deserialized == signal

    where:
    signal << [
      new ModuleExecutionResult(12345, 67890, false, false, false, false, 0, Collections.emptyList()),
      new ModuleExecutionResult(12345, 67890, true, false, true, true, 1, Collections.singletonList(new TestFramework("junit", "4.13.2"))),
      new ModuleExecutionResult(12345, 67890, false, true, true, false, 2, Arrays.asList(new TestFramework("junit", "4.13.2"), new TestFramework("junit", "5.9.2"))),
      new ModuleExecutionResult(12345, 67890, false, false, false, true, 3, Arrays.asList(new TestFramework("junit", null), new TestFramework("junit", "5.9.2"))),
      new ModuleExecutionResult(12345, 67890, true, true, true, true, Integer.MAX_VALUE, Arrays.asList(new TestFramework("junit", "4.13.2"), new TestFramework(null, "5.9.2")))
    ]
  }
}

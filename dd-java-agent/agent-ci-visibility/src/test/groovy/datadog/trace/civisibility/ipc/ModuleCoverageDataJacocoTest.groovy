package datadog.trace.civisibility.ipc

import spock.lang.Specification

class ModuleCoverageDataJacocoTest extends Specification {

  def "test serialization and deserialization: #signal"() {
    when:
    def bytes = signal.serialize()
    def deserialized = ModuleCoverageDataJacoco.deserialize(bytes)

    then:
    deserialized == signal

    where:
    signal << [
      new ModuleCoverageDataJacoco(12345, 67890, null),
      new ModuleCoverageDataJacoco(12345, 67890, new byte[]{}),
      new ModuleCoverageDataJacoco(12345, 67890, new byte[]{
        1, 2, 3, 4, 5, 6
      })
    ]
  }
}

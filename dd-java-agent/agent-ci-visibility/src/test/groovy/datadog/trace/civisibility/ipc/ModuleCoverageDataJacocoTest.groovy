package datadog.trace.civisibility.ipc

import datadog.trace.api.DD128bTraceId
import datadog.trace.api.DDTraceId
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
      new ModuleCoverageDataJacoco(DDTraceId.from(12345), 67890, null),
      new ModuleCoverageDataJacoco(DDTraceId.from(12345), 67890, new byte[]{}),
      new ModuleCoverageDataJacoco(DD128bTraceId.from(12345, 67890), 67890, new byte[]{
        1, 2, 3, 4, 5, 6
      })
    ]
  }
}

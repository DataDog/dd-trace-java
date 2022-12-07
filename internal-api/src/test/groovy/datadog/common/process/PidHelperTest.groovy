package datadog.common.process

import spock.lang.Specification

import java.util.function.Supplier

class PidHelperTest extends Specification {

  def "Expect PID to be present since we run tests in systems where we can load it"() {
    given:
    Supplier<Long> supplier = new JnrProcessIdSupplier()
    when:
    PidHelper.computeIfAbsent(supplier)
    then:
    PidHelper.PID > 0
  }
}

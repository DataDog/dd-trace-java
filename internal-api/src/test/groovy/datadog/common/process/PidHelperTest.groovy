package datadog.common.process

import spock.lang.Specification

class PidHelperTest extends Specification {

  def "Expect PID to be present since we run tests in systems where we can load it"() {
    expect:
    PidHelper.PID > 0
  }
}

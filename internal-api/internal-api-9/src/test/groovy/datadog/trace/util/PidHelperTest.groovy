package datadog.trace.util

import datadog.trace.test.util.DDSpecification

class PidHelperTest extends DDSpecification {

  def "PID is available everywhere we test"() {
    expect:
    !PidHelper.getPid().isEmpty()
  }
}

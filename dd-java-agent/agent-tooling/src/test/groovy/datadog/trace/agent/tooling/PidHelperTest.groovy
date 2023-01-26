package datadog.trace.agent.tooling

import datadog.trace.test.util.DDSpecification
import datadog.trace.util.PidHelper

class PidHelperTest extends DDSpecification {

  def "PID is available everywhere we test"() {
    expect:
    !PidHelper.getPid().isEmpty()
  }
}

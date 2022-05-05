package datadog.communication.ddagent

import datadog.trace.test.util.DDSpecification

class DroppingPolicyTest extends DDSpecification {

  def "test disabled dropping policy"() {
    expect:
    !DroppingPolicy.DISABLED.active()
  }
}

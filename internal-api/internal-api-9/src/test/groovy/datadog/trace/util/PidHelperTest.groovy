package datadog.trace.util

import datadog.trace.test.util.DDSpecification
import net.bytebuddy.agent.ByteBuddyAgent

class PidHelperTest extends DDSpecification {

  def "PID is available everywhere we test"() {
    expect:
    !PidHelper.getPid().isEmpty()
  }

  def "JPS via jvmstat is used when possible"() {
    when:
    def inst = ByteBuddyAgent.install()
    JPMSJPSAccess.patchModuleAccess(inst)

    then:
    JPSUtils.VMPids != null
  }
}

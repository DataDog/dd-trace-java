package datadog.trace.util

import datadog.trace.test.util.DDSpecification

import java.lang.instrument.Instrumentation

class JPSUtilsTest extends DDSpecification {

  def "PID is available everywhere we test"() {
    when:
    Instrumentation inst = ByteBuddyAgent.getInstrumentation()
    Class.forName("datadog.trace.util.JPMSJPSAccess")
      .getMethod("patchModuleAccess")
      .invoke(inst)

    then:
    JPSUtils.getVMPids().size() > 0
  }
}

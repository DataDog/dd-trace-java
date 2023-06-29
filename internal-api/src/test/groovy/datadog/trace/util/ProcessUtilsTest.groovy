package datadog.trace.util

import spock.lang.Specification

class ProcessUtilsTest extends Specification {

  def "Current JVM path is available everywhere we test"() {
    expect:
    !ProcessUtils.getCurrentJvmPath().isEmpty()
  }
}

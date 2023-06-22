package datadog.trace.util

import spock.lang.Specification

class ProcessUtilsTest extends Specification {

  def "Current executable path is available everywhere we test"() {
    expect:
    !ProcessUtils.getCurrentExecutablePath().isEmpty()
  }
}

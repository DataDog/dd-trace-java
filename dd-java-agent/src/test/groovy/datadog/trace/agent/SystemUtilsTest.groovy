package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout

import jvmbootstraptest.SystemUtilsCheck
import jvmbootstraptest.TestSecurityManager

@Timeout(30)
class SystemUtilsTest extends Specification {
  def "no env access"() {
    expect:
    SystemUtilsCheck.runTestJvm(TestSecurityManager.NoEnvAccess, true) == 0
  }

  def "minimal property access"() {
    expect:
    SystemUtilsCheck.runTestJvm(TestSecurityManager.MinimalPropertyAccess, true) == 0
  }
}

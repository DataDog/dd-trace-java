package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout

import jvmbootstraptest.SecurityManagerCheck
import jvmbootstraptest.TestSecurityManager

@Timeout(30)
class SecurityManagerTest extends Specification {
  def "no env access"() {
    expect:
    SecurityManagerCheck.runTestJvm(TestSecurityManager.NoEnvAccess) == 0
  }

  def "minimal property access"() {
    expect:
    SecurityManagerCheck.runTestJvm(TestSecurityManager.MinimalPropertyAccess) == 0
  }

  def "no process execution"() {
    expect:
    SecurityManagerCheck.runTestJvm(TestSecurityManager.NoProcessExecution) == 0
  }

  def "no network access"() {
    expect:
    SecurityManagerCheck.runTestJvm(TestSecurityManager.NoNetworkAccess) == 0
  }
}
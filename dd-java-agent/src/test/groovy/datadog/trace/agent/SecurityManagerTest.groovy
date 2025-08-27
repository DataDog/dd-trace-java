package datadog.trace.agent

import datadog.environment.JavaVirtualMachine
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Requires

import jvmbootstraptest.SecurityManagerCheck
import jvmbootstraptest.TestSecurityManager

@Timeout(30)
// SecurityManager is permanently disabled as of JDK 24
@Requires({
  !JavaVirtualMachine.isJavaVersionAtLeast(24)
})
class SecurityManagerTest extends Specification {
  def "no env access"() {
    expect:
    SecurityManagerCheck.runTestJvm(TestSecurityManager.NoEnvAccess) == 0
  }

  def "minimal property access"() {
    expect:
    SecurityManagerCheck.runTestJvm(TestSecurityManager.MinimalPropertyAccess, true) == 0
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

package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.IgnoreIf
import datadog.trace.api.Platform

import jvmbootstraptest.SecurityManagerCheck
import jvmbootstraptest.TestSecurityManager

@Timeout(30)
@IgnoreIf(reason = "SecurityManager is permanently disabled as of JDK 24", value = {
  Platform.isJavaVersionAtLeast(24)
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

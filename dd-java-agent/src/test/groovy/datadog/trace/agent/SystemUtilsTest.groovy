package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout

import jvmbootstraptest.SystemUtilsCheck
import jvmbootstraptest.TestSecurityManager

@Timeout(30)
class SystemUtilsTest extends Specification {
  def "no env access"() {
    when:
    def result = SystemUtilsCheck.runTestJvm(TestSecurityManager.NoEnvAccess, true)
    System.out.println("SYSTEMUTILSTEST - no env access result is " + result)

    then:
    result == 0
  }

  def "minimal property access"() {
    when:
    def result = SystemUtilsCheck.runTestJvm(TestSecurityManager.MinimalPropertyAccess, true)
    System.out.println("SYSTEMUTILSTEST - minimal property access result is " + result)

    then:
    result == 0
  }
}

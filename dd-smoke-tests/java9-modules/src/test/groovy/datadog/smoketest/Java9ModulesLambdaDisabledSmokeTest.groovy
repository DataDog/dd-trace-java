package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import datadog.environment.OperatingSystem
import spock.lang.IgnoreIf

/** Verifies lambda transformation can be disabled for a named module. */
@IgnoreIf({
  OperatingSystem.isLinux() && OperatingSystem.architecture().isArm64() && JavaVirtualMachine.isJ9()
})
class Java9ModulesLambdaDisabledSmokeTest extends Java9ModulesSmokeTest {
  @Override
  def javaProperties() {
    return super.javaProperties() + "-Ddd.trace.lambda.enabled=false"
  }
}

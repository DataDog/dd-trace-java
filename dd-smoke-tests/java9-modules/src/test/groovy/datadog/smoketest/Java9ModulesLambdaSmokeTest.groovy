package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import datadog.environment.OperatingSystem
import spock.lang.IgnoreIf

/**
 * Runs the modular application with lambda field-injection enabled. The lambda's generated class
 * belongs to the application's named module, which reaches the injected types only if the agent
 * adds a read edge while transforming it.
 */
@IgnoreIf({
  OperatingSystem.isLinux() && OperatingSystem.architecture().isArm64() && JavaVirtualMachine.isJ9()
})
class Java9ModulesLambdaSmokeTest extends Java9ModulesSmokeTest {
  @Override
  def javaProperties() {
    return super.javaProperties() + "-Ddd.trace.lambda.enabled=true"
  }
}

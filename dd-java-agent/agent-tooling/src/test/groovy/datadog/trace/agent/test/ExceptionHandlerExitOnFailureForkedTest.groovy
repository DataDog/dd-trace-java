package datadog.trace.agent.test

import ch.qos.logback.classic.Level
import datadog.environment.JavaVirtualMachine
import spock.lang.IgnoreIf

@IgnoreIf(reason = "SecurityManager used in the test is marked for removal and throws exceptions", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(21)
})
class ExceptionHandlerExitOnFailureForkedTest extends BaseExceptionHandlerTest {
  @Override
  protected void changeConfig() {
    injectSysConfig("dd.trace.internal.exit.on.failure", "true")
  }

  @Override
  protected int expectedFailureExitStatus() {
    return 1
  }

  @Override
  protected Level expectedFailureLogLevel() {
    return Level.ERROR
  }
}

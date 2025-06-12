package datadog.trace.agent.test

import ch.qos.logback.classic.Level
import datadog.environment.JavaVirtualMachine
import spock.lang.IgnoreIf

@IgnoreIf(reason = "SecurityManager used in the test is marked for removal and throws exceptions", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(21)
})
class AppSecDisabledExceptionHandlerForkedTest extends BaseExceptionHandlerTest {

  @Override
  protected void changeConfig() {
    injectSysConfig('appsec.enabled', 'false')
  }

  @Override
  protected int expectedFailureExitStatus() {
    return 0
  }

  @Override
  protected Level expectedFailureLogLevel() {
    return Level.DEBUG
  }

  protected boolean expectedBlockingException() {
    return false
  }
}

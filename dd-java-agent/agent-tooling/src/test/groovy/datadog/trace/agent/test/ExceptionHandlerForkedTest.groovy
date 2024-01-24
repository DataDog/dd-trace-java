package datadog.trace.agent.test

import ch.qos.logback.classic.Level
import datadog.trace.api.Platform
import spock.lang.IgnoreIf

@IgnoreIf(reason = "SecurityManager used in the test is marked for removal and throws exceptions", value = {
  Platform.isJavaVersionAtLeast(21)
})
class ExceptionHandlerForkedTest extends BaseExceptionHandlerTest {
  @Override
  protected void changeConfig() {
  }

  @Override
  protected int expectedFailureExitStatus() {
    return 0
  }

  @Override
  protected Level expectedFailureLogLevel() {
    return Level.DEBUG
  }
}

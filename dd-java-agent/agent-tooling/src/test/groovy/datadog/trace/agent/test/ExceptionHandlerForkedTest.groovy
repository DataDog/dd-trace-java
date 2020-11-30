package datadog.trace.agent.test

import ch.qos.logback.classic.Level

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

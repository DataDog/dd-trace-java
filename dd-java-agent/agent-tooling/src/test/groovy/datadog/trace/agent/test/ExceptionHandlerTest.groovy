package datadog.trace.agent.test

import ch.qos.logback.classic.Level

class ExceptionHandlerTest extends BaseExceptionHandlerTest {
  @Override
  protected void changeConfig() {
  }

  @Override
  protected void resetConfig() {
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

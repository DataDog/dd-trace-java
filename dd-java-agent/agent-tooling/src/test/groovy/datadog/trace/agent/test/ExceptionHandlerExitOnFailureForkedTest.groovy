package datadog.trace.agent.test

import ch.qos.logback.classic.Level

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

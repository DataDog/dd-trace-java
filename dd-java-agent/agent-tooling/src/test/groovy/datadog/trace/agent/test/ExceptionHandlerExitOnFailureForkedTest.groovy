package datadog.trace.agent.test

import ch.qos.logback.classic.Level
import datadog.trace.agent.test.utils.ConfigUtils

class ExceptionHandlerExitOnFailureForkedTest extends BaseExceptionHandlerTest {
  @Override
  protected void changeConfig() {
    ConfigUtils.updateConfig {
      System.setProperty("dd.trace.internal.exit.on.failure", "true")
    }
  }

  @Override
  protected void resetConfig() {
    ConfigUtils.updateConfig {
      System.clearProperty("dd.trace.internal.exit.on.failure")
    }
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

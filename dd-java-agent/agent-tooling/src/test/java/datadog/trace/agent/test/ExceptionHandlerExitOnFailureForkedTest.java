package datadog.trace.agent.test;

import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;

import ch.qos.logback.classic.Level;
import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = INTERNAL_EXIT_ON_FAILURE, value = "true")
class ExceptionHandlerExitOnFailureForkedTest extends BaseExceptionHandlerTest {
  @Override
  protected int expectedFailureExitStatus() {
    return 1;
  }

  @Override
  protected Level expectedFailureLogLevel() {
    return Level.ERROR;
  }
}

package datadog.trace.agent.test;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;

import ch.qos.logback.classic.Level;
import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = APPSEC_ENABLED, value = "false")
class AppSecDisabledExceptionHandlerForkedTest extends BaseExceptionHandlerTest {
  @Override
  protected int expectedFailureExitStatus() {
    return 0;
  }

  @Override
  protected Level expectedFailureLogLevel() {
    return Level.DEBUG;
  }

  @Override
  protected boolean expectedBlockingException() {
    return false;
  }
}

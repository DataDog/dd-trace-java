package datadog.trace.agent.test;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static org.junit.jupiter.api.condition.JRE.JAVA_20;

import ch.qos.logback.classic.Level;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.condition.EnabledForJreRange;

@WithConfig(key = APPSEC_ENABLED, value = "false")
@EnabledForJreRange(max = JAVA_20)
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

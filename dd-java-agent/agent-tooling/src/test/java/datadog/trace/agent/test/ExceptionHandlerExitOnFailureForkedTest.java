package datadog.trace.agent.test;

import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static org.junit.jupiter.api.condition.JRE.JAVA_20;

import ch.qos.logback.classic.Level;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.condition.EnabledForJreRange;

@WithConfig(key = INTERNAL_EXIT_ON_FAILURE, value = "true")
@EnabledForJreRange(max = JAVA_20)
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

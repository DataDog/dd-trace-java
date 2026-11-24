package datadog.trace.agent.test;

import static org.junit.jupiter.api.condition.JRE.JAVA_20;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.condition.EnabledForJreRange;

@EnabledForJreRange(max = JAVA_20)
class ExceptionHandlerForkedTest extends BaseExceptionHandlerTest {
  @Override
  protected int expectedFailureExitStatus() {
    return 0;
  }

  @Override
  protected Level expectedFailureLogLevel() {
    return Level.DEBUG;
  }
}

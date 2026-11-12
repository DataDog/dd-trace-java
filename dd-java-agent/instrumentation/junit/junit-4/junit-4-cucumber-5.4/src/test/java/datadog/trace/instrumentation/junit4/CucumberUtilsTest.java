package datadog.trace.instrumentation.junit4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.util.MethodHandles;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.slf4j.LoggerFactory;

/** Scenario descriptions whose unique ID is not a Cucumber pickle ID. */
class CucumberUtilsTest {

  /** A plain JUnit description: its unique ID is its display name, not a PickleId. */
  private static final Description NOT_A_PICKLE =
      Description.createTestDescription("Feature Name", "Scenario Name");

  @Test
  void pickleUriIsAbsentWithoutError() {
    assertNull(withoutErrors(() -> CucumberUtils.getPickleUri(NOT_A_PICKLE)));
  }

  @Test
  void pickleLineIsAbsentWithoutError() {
    assertNull(withoutErrors(() -> CucumberUtils.getPickleLine(NOT_A_PICKLE)));
  }

  @Test
  void testIdentifierFallsBackToDescriptionNamesWithoutError() {
    TestIdentifier identifier = withoutErrors(() -> CucumberUtils.toTestIdentifier(NOT_A_PICKLE));

    assertEquals("Feature Name", identifier.getSuite());
    assertEquals("Scenario Name", identifier.getName());
  }

  private static <T> T withoutErrors(Supplier<T> call) {
    Logger logger = (Logger) LoggerFactory.getLogger(MethodHandles.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      T result = call.get();
      List<String> errors =
          appender.list.stream()
              .filter(event -> event.getLevel() == Level.ERROR)
              .map(ILoggingEvent::getFormattedMessage)
              .collect(Collectors.toList());
      assertTrue(errors.isEmpty(), () -> "errors logged: " + errors);
      return result;
    } finally {
      logger.detachAppender(appender);
    }
  }
}

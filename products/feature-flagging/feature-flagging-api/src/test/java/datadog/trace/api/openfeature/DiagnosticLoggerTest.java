package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;

class DiagnosticLoggerTest {
  private final Logger backend = (Logger) LoggerFactory.getLogger(DiagnosticLoggerTest.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private String previousTraceDebug;
  private Level previousLevel;

  @BeforeEach
  void setup() {
    previousTraceDebug = System.getProperty("dd.trace.debug");
    previousLevel = backend.getLevel();
    backend.setLevel(Level.DEBUG);
    appender.start();
    backend.addAppender(appender);
  }

  @AfterEach
  void cleanup() {
    setTraceDebug(previousTraceDebug);
    backend.detachAppender(appender);
    appender.stop();
    backend.setLevel(previousLevel);
  }

  @TableTest({
    "Scenario             | Trace debug | Level | Enabled",
    "Both enabled         | true        | DEBUG | true   ",
    "Trace debug disabled | false       | DEBUG | false  ",
    "Trace debug unset    |             | DEBUG | false  ",
    "Application filtered | true        | INFO  | false  ",
    "Both disabled        | false       | INFO  | false  "
  })
  void requiresTraceDebugAndApplicationDebug(
      String scenario, String traceDebug, String level, boolean enabled) {
    setTraceDebug(traceDebug);
    backend.setLevel(Level.toLevel(level));
    DiagnosticLogger logger = DiagnosticLogger.getLogger(DiagnosticLoggerTest.class);

    assertEquals(enabled, logger.isDebugEnabled());
    logDebugOverloads(logger);

    assertEquals(enabled ? 4 : 0, appender.list.size());
  }

  @Test
  void cachesTraceDebugAtLoggerCreation() {
    setTraceDebug("false");
    DiagnosticLogger disabled = DiagnosticLogger.getLogger(DiagnosticLoggerTest.class);
    setTraceDebug("true");
    DiagnosticLogger enabled = DiagnosticLogger.getLogger(DiagnosticLoggerTest.class);
    setTraceDebug("false");

    assertFalse(disabled.isDebugEnabled());
    assertTrue(enabled.isDebugEnabled());
    disabled.debug("disabled");
    enabled.debug("enabled");
    assertEquals(1, appender.list.size());
    assertEquals("enabled", appender.list.get(0).getFormattedMessage());
  }

  @Test
  void keepsOtherLevelsWhenTraceDebugIsDisabled() {
    setTraceDebug("false");
    DiagnosticLogger logger = DiagnosticLogger.getLogger(DiagnosticLoggerTest.class);
    logger.info("information {}", "value");
    logger.warn("warning");
    logger.warn("warning {}", "value");
    logger.error("error", new IllegalStateException("failure"));

    assertEquals(4, appender.list.size());
    assertEquals(Level.INFO, appender.list.get(0).getLevel());
    assertEquals(Level.WARN, appender.list.get(1).getLevel());
    assertEquals(Level.WARN, appender.list.get(2).getLevel());
    assertEquals(Level.ERROR, appender.list.get(3).getLevel());
  }

  private static void logDebugOverloads(DiagnosticLogger logger) {
    logger.debug("message");
    logger.debug("one {}", "first");
    logger.debug("two {} {}", "first", "second");
    logger.debug("three {} {} {}", "first", "second", "third");
  }

  private static void setTraceDebug(String value) {
    if (value == null) {
      System.clearProperty("dd.trace.debug");
    } else {
      System.setProperty("dd.trace.debug", value);
    }
  }
}

package datadog.trace.logging.ddlogger;

import static datadog.trace.logging.LogValidator.createValidator;
import static datadog.trace.logging.LogValidator.exception;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.slf4j.impl.StaticMarkerBinder;
import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogValidator;
import datadog.trace.logging.simplelogger.SLCompatFactory;
import datadog.trace.logging.simplelogger.SLCompatSettings;
import datadog.trace.logging.simplelogger.SLCompatSettings.Defaults;
import datadog.trace.logging.simplelogger.SLCompatSettings.Keys;
import datadog.trace.logging.simplelogger.SLCompatSettings.Names;
import datadog.trace.logging.simplelogger.SLCompatSettingsTestBridge;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.tabletest.junit.TableTest;

class DDLoggerTest {

  private final Marker marker =
      StaticMarkerBinder.getSingleton().getMarkerFactory().getMarker("marker");

  private void validateEnabled(
      Logger logger, boolean trace, boolean debug, boolean info, boolean warn, boolean error) {
    assertEquals(trace, logger.isTraceEnabled());
    assertEquals(trace, logger.isTraceEnabled(marker));

    assertEquals(debug, logger.isDebugEnabled());
    assertEquals(debug, logger.isDebugEnabled(marker));

    assertEquals(info, logger.isInfoEnabled());
    assertEquals(info, logger.isInfoEnabled(marker));

    assertEquals(warn, logger.isWarnEnabled());
    assertEquals(warn, logger.isWarnEnabled(marker));

    assertEquals(error, logger.isErrorEnabled());
    assertEquals(error, logger.isErrorEnabled(marker));
  }

  @TableTest({
    "scenario | level | trace | debug | info  | warn  | error",
    "trace    | trace | true  | true  | true  | true  | true ",
    "debug    | debug | false | true  | true  | true  | true ",
    "info     | info  | false | false | true  | true  | true ",
    "warn     | warn  | false | false | false | true  | true ",
    "error    | error | false | false | false | false | true ",
    "off      | off   | false | false | false | false | false"
  })
  void testEnabledAndLogLevelSwitching(
      String level, boolean trace, boolean debug, boolean info, boolean warn, boolean error) {
    // when
    Properties props = new Properties();
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, level);
    DDLoggerFactory factory = new DDLoggerFactory(new SLCompatFactory(props));
    Logger logger = factory.getLogger("foo.bar");

    // then
    validateEnabled(logger, trace, debug, info, warn, error);
    factory.switchLevel(LogLevel.TRACE);
    validateEnabled(logger, true, true, true, true, true);
    factory.switchLevel(LogLevel.DEBUG);
    validateEnabled(logger, false, true, true, true, true);
    factory.switchLevel(LogLevel.INFO);
    validateEnabled(logger, false, false, true, true, true);
    factory.switchLevel(LogLevel.WARN);
    validateEnabled(logger, false, false, false, true, true);
    factory.switchLevel(LogLevel.ERROR);
    validateEnabled(logger, false, false, false, false, true);
    factory.switchLevel(LogLevel.OFF);
    validateEnabled(logger, false, false, false, false, false);
    factory.restore();
    validateEnabled(logger, trace, debug, info, warn, error);
  }

  @TableTest({
    "scenario | level | trace | debug | info  | warn  | error",
    "trace    | trace | true  | true  | true  | true  | true ",
    "debug    | debug | false | true  | true  | true  | true ",
    "info     | info  | false | false | true  | true  | true ",
    "warn     | warn  | false | false | false | true  | true ",
    "error    | error | false | false | false | false | true ",
    "off      | off   | false | false | false | false | false"
  })
  void testLogging(
      String level, boolean trace, boolean debug, boolean info, boolean warn, boolean error) {
    // when
    Properties props = new Properties();
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, level);
    props.setProperty(Keys.SHOW_THREAD_NAME, "false");
    LogValidator validator = createValidator("foo.bar");
    PrintStream printStream = new PrintStream(validator.getOutputStream(), true);
    SLCompatSettings settings = new SLCompatSettings(props, null, printStream);
    DDLoggerFactory factory = new DDLoggerFactory(new SLCompatFactory(props, settings));
    Logger logger = factory.getLogger("foo.bar");

    // then
    // TRACE
    logger.trace("m1");
    validator.trace(trace, "m1");
    logger.trace("m1 {}", "a");
    validator.trace(trace, "m1 a");
    logger.trace("m1 {} {}", "b1", "b2");
    validator.trace(trace, "m1 b1 b2");
    logger.trace("m1 {} {} {} {}", "c1", "c2", "c3", "c4");
    validator.trace(trace, "m1 c1 c2 c3 c4");
    logger.trace("m1", exception("d"));
    validator.trace(trace, "m1", "d");
    logger.trace(marker, "m2");
    validator.trace(trace, marker, "m2");
    logger.trace(marker, "m2 {}", "e");
    validator.trace(trace, marker, "m2 e");
    logger.trace(marker, "m2 {} {}", "f2", "f1");
    validator.trace(trace, marker, "m2 f2 f1");
    logger.trace(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1");
    validator.trace(trace, marker, "m2 g4 g3 g2 g1");
    logger.trace("m2", exception("h"));
    validator.trace(trace, "m2", "h");

    // DEBUG
    logger.debug("m1");
    validator.debug(debug, "m1");
    logger.debug("m1 {}", "a");
    validator.debug(debug, "m1 a");
    logger.debug("m1 {} {}", "b1", "b2");
    validator.debug(debug, "m1 b1 b2");
    logger.debug("m1 {} {} {} {}", "c1", "c2", "c3", "c4");
    validator.debug(debug, "m1 c1 c2 c3 c4");
    logger.debug("m1", exception("d"));
    validator.debug(debug, "m1", "d");
    logger.debug(marker, "m2");
    validator.debug(debug, marker, "m2");
    logger.debug(marker, "m2 {}", "e");
    validator.debug(debug, marker, "m2 e");
    logger.debug(marker, "m2 {} {}", "f2", "f1");
    validator.debug(debug, marker, "m2 f2 f1");
    logger.debug(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1");
    validator.debug(debug, marker, "m2 g4 g3 g2 g1");
    logger.debug("m2", exception("h"));
    validator.debug(debug, "m2", "h");

    // INFO
    logger.info("m1");
    validator.info(info, "m1");
    logger.info("m1 {}", "a");
    validator.info(info, "m1 a");
    logger.info("m1 {} {}", "b1", "b2");
    validator.info(info, "m1 b1 b2");
    logger.info("m1 {} {} {} {}", "c1", "c2", "c3", "c4");
    validator.info(info, "m1 c1 c2 c3 c4");
    logger.info("m1", exception("d"));
    validator.info(info, "m1", "d");
    logger.info(marker, "m2");
    validator.info(info, marker, "m2");
    logger.info(marker, "m2 {}", "e");
    validator.info(info, marker, "m2 e");
    logger.info(marker, "m2 {} {}", "f2", "f1");
    validator.info(info, marker, "m2 f2 f1");
    logger.info(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1");
    validator.info(info, marker, "m2 g4 g3 g2 g1");
    logger.info("m2", exception("h"));
    validator.info(info, "m2", "h");

    // WARN
    logger.warn("m1");
    validator.warn(warn, "m1");
    logger.warn("m1 {}", "a");
    validator.warn(warn, "m1 a");
    logger.warn("m1 {} {}", "b1", "b2");
    validator.warn(warn, "m1 b1 b2");
    logger.warn("m1 {} {} {} {}", "c1", "c2", "c3", "c4");
    validator.warn(warn, "m1 c1 c2 c3 c4");
    logger.warn("m1", exception("d"));
    validator.warn(warn, "m1", "d");
    logger.warn(marker, "m2");
    validator.warn(warn, marker, "m2");
    logger.warn(marker, "m2 {}", "e");
    validator.warn(warn, marker, "m2 e");
    logger.warn(marker, "m2 {} {}", "f2", "f1");
    validator.warn(warn, marker, "m2 f2 f1");
    logger.warn(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1");
    validator.warn(warn, marker, "m2 g4 g3 g2 g1");
    logger.warn("m2", exception("h"));
    validator.warn(warn, "m2", "h");

    // ERROR
    logger.error("m1");
    validator.error(error, "m1");
    logger.error("m1 {}", "a");
    validator.error(error, "m1 a");
    logger.error("m1 {} {}", "b1", "b2");
    validator.error(error, "m1 b1 b2");
    logger.error("m1 {} {} {} {}", "c1", "c2", "c3", "c4");
    validator.error(error, "m1 c1 c2 c3 c4");
    logger.error("m1", exception("d"));
    validator.error(error, "m1", "d");
    logger.error(marker, "m2");
    validator.error(error, marker, "m2");
    logger.error(marker, "m2 {}", "e");
    validator.error(error, marker, "m2 e");
    logger.error(marker, "m2 {} {}", "f2", "f1");
    validator.error(error, marker, "m2 f2 f1");
    logger.error(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1");
    validator.error(error, marker, "m2 g4 g3 g2 g1");
    logger.error("m2", exception("h"));
    validator.error(error, "m2", "h");
  }

  // remove LogLevel.OFF
  @ParameterizedTest
  @EnumSource(value = LogLevel.class, names = "OFF", mode = EnumSource.Mode.EXCLUDE)
  void testLoggingWithAnEmbeddedExceptionInTheMessage(LogLevel level) {
    // setup
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    Logger logger = createEmbeddedExceptionLogger(level, outputStream);

    // when
    try {
      throw new IOException("wrong");
    } catch (Exception exception) {
      switch (level) {
        case TRACE:
          logger.trace("log", exception);
          break;
        case DEBUG:
          logger.debug("log", exception);
          break;
        case INFO:
          logger.info("log", exception);
          break;
        case WARN:
          logger.warn("log", exception);
          break;
        case ERROR:
          logger.error("log", exception);
          break;
        default:
          logger.error("Weird Level " + level);
      }
    }

    // then
    String output = outputStream.toString();
    assertTrue(
        output.matches(
            "^.* "
                + level
                + " foo - log \\[exception:java\\.io\\.IOException: wrong\\. at .*\\]\n$"),
        output);
  }

  // remove LogLevel.OFF
  @ParameterizedTest
  @EnumSource(value = LogLevel.class, names = "OFF", mode = EnumSource.Mode.EXCLUDE)
  void testLoggingWithAnEmbeddedExceptionInTheMessageAndVarargs(LogLevel level) {
    // setup
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    Logger logger = createEmbeddedExceptionLogger(level, outputStream);

    // when
    try {
      throw new IOException("wrong");
    } catch (Exception exception) {
      logVarargs(logger, level, "log {}", "some", exception);
    }

    // then
    String output = outputStream.toString();
    assertTrue(
        output.matches(
            "^.* "
                + level
                + " foo - log some more \\[exception:java\\.io\\.IOException: wrong\\. at .*\\]\n$"),
        output);
  }

  private static Logger createEmbeddedExceptionLogger(
      LogLevel level, ByteArrayOutputStream outputStream) {
    Properties props = new Properties();
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, level.toString());
    props.setProperty(Keys.EMBED_EXCEPTION, "true");
    PrintStream printStream = new PrintStream(outputStream, true);
    SLCompatSettings settings = new SLCompatSettings(props, null, printStream);
    DDLoggerFactory factory = new DDLoggerFactory(new SLCompatFactory(props, settings));
    return factory.getLogger("foo");
  }

  private static void logVarargs(
      Logger logger, LogLevel level, String format, Object... arguments) {
    String fmt = format + " more";
    switch (level) {
      case TRACE:
        logger.trace(fmt, arguments);
        break;
      case DEBUG:
        logger.debug(fmt, arguments);
        break;
      case INFO:
        logger.info(fmt, arguments);
        break;
      case WARN:
        logger.warn(fmt, arguments);
        break;
      case ERROR:
        logger.error(fmt, arguments);
        break;
      default:
        logger.error("Weird Level " + level);
    }
  }

  @Test
  void testLogOutputToAFile(@TempDir File dir) throws FileNotFoundException {
    // setup
    File file = new File(dir, "log");
    Properties props = new Properties();
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, "DEBUG");
    props.setProperty(Keys.SHOW_THREAD_NAME, "false");
    props.setProperty(Keys.LOG_FILE, file.getAbsolutePath());
    SLCompatSettings settings = new SLCompatSettings(props);
    try (PrintStream stream = SLCompatSettingsTestBridge.printStream(settings);
        Scanner scanner = new Scanner(file)) {
      DDLoggerFactory factory = new DDLoggerFactory(new SLCompatFactory(props, settings));
      Logger logger = factory.getLogger("bar.baz");
      logger.trace("test trace");
      logger.debug("test debug");
      logger.info("test info");
      logger.warn("test warn");
      logger.error("test error");
      stream.flush();

      // expect
      assertTrue(file.exists());
      assertTrue(scanner.hasNextLine());
      // We should not log at trace level
      assertEquals("DEBUG bar.baz - test debug", scanner.nextLine());
      assertEquals("INFO bar.baz - test info", scanner.nextLine());
      assertEquals("WARN bar.baz - test warn", scanner.nextLine());
      assertEquals("ERROR bar.baz - test error", scanner.nextLine());
    }
  }

  @TableTest({
    "scenario     | level | warn | expectedLevel | expectedWarn",
    "default warn | TRACE |      | TRACE         | WARN        ",
    "custom warn  | ERROR | WRN  | ERROR         | WRN         "
  })
  void testLoggerSettingsDescription(
      LogLevel level, String warn, String expectedLevel, String expectedWarn) {
    // setup
    Properties props = new Properties();
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, level.toString());
    props.setProperty(Keys.SHOW_THREAD_NAME, "false");
    props.setProperty(Keys.SHOW_DATE_TIME, "true");
    if (warn != null) {
      props.setProperty(Keys.WARN_LEVEL_STRING, warn);
    }
    SLCompatSettings settings = new SLCompatSettings(props);
    SLCompatFactory factory = new SLCompatFactory(props, settings);

    // expect
    Map<String, Object> expected = new HashMap<>();
    expected.put(Names.WARN_LEVEL_STRING, expectedWarn);
    expected.put(Names.LEVEL_IN_BRACKETS, Defaults.LEVEL_IN_BRACKETS);
    expected.put(Names.LOG_FILE, Defaults.LOG_FILE);
    expected.put(Names.SHOW_LOG_NAME, Defaults.SHOW_LOG_NAME);
    expected.put(Names.SHOW_SHORT_LOG_NAME, Defaults.SHOW_SHORT_LOG_NAME);
    expected.put(Names.SHOW_THREAD_NAME, false);
    expected.put(Names.SHOW_DATE_TIME, true);
    expected.put(Names.JSON_ENABLED, Defaults.JSON_ENABLED);
    expected.put(Names.DATE_TIME_FORMAT, "relative");
    expected.put(Names.DEFAULT_LOG_LEVEL, expectedLevel);
    expected.put(Names.EMBED_EXCEPTION, Defaults.EMBED_EXCEPTION);
    expected.put(Names.CONFIGURATION_FILE, Defaults.CONFIGURATION_FILE);
    assertEquals(expected, factory.getSettingsDescription());
  }
}

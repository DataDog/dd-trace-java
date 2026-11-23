package datadog.trace.logging.ddlogger;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.api.telemetry.LogCollector.RawLogMessage;
import datadog.trace.logging.simplelogger.SLCompatFactory;
import datadog.trace.logging.simplelogger.SLCompatSettings;
import java.util.Collection;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.Marker;

class DDTelemetryLoggerTest {

  @BeforeEach
  void setup() {
    LogCollector.get().drain();
  }

  @AfterEach
  void cleanup() {
    LogCollector.get().drain();
  }

  private static DDLogger createLogger(String level) {
    String name = "foo.bar";
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, level);
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false");
    SLCompatSettings settings = new SLCompatSettings(props);
    SLCompatFactory factory = new SLCompatFactory(props, settings);
    return new DDTelemetryLogger(factory.loggerHelperForName(name), name);
  }

  @ParameterizedTest(name = "do not log {0}(format, obj) call with level {0}")
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void doNotLogFormatObj(String level) {
    // given
    String format = "msg {}";
    Object arg = 42;
    Logger logger = createLogger(level);

    // when
    log(logger, level, format, arg);
    Collection<RawLogMessage> collection = LogCollector.get().drain();

    // then
    assertTrue(collection.isEmpty());
  }

  @ParameterizedTest(name = "do not log {0}(format, obj, obj, obj) call with level {0}")
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void doNotLogFormatObjObjObj(String level) {
    // given
    String format = "msg {} {} {}";
    Object arg = 42;
    Logger logger = createLogger(level);

    // when
    logVarargs(logger, level, format, arg, arg, arg);
    Collection<RawLogMessage> collection = LogCollector.get().drain();

    // then
    assertTrue(collection.isEmpty());
  }

  @ParameterizedTest(name = "log {0}(SEND_TELEMETRY, format, obj) call with level {0}")
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void logSendTelemetryFormatObj(String level) {
    // given
    String format = "msg {}";
    Object arg = 42;
    Logger logger = createLogger(level);

    // when
    log(logger, level, SEND_TELEMETRY, format, arg);
    Collection<RawLogMessage> collection = LogCollector.get().drain();

    // then
    assertSingleMessage(format, collection);
  }

  @ParameterizedTest(name = "log {0}(msg, throwable) call with level {0}")
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void logMsgThrowable(String level) {
    // given
    String format = "msg";
    Exception throwable = new Exception();
    Logger logger = createLogger(level);

    // when
    log(logger, level, format, throwable);
    Collection<RawLogMessage> collection = LogCollector.get().drain();

    // then
    assertSingleMessage(format, collection);
  }

  @ParameterizedTest(name = "log {0}(msg, obj, throwable) call with level {0}")
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void logMsgObjThrowable(String level) {
    // given
    String format = "msg {}";
    Object arg = 42;
    Exception throwable = new Exception();
    Logger logger = createLogger(level);

    // when
    log(logger, level, format, arg, throwable);
    Collection<RawLogMessage> collection = LogCollector.get().drain();

    // then
    assertSingleMessage(format, collection);
  }

  @ParameterizedTest(name = "log {0}(msg, obj, obj, throwable) call with level {0}")
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void logMsgObjObjThrowable(String level) {
    // given
    String format = "msg {}";
    Object arg = 42;
    Exception throwable = new Exception();
    Logger logger = createLogger(level);

    // when
    logVarargs(logger, level, format, arg, arg, throwable);
    Collection<RawLogMessage> collection = LogCollector.get().drain();

    // then
    assertSingleMessage(format, collection);
  }

  @ParameterizedTest(name = "do not log {0}(EXCLUDE_TELEMETRY, msg, throwable) call with level {0}")
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void doNotLogExcludeTelemetryMsgThrowable(String level) {
    // given
    String format = "msg";
    Exception throwable = new Exception();
    Logger logger = createLogger(level);

    // when
    log(logger, level, EXCLUDE_TELEMETRY, format, throwable);
    Collection<RawLogMessage> collection = LogCollector.get().drain();

    // then
    assertTrue(collection.isEmpty());
  }

  private static void assertSingleMessage(
      String expectedMessage, Collection<RawLogMessage> collection) {
    assertEquals(1, collection.size());
    assertEquals(expectedMessage, collection.iterator().next().message);
  }

  /** Calls {@code logger.<level>(String, Object)}. */
  private static void log(Logger logger, String level, String format, Object arg) {
    switch (level) {
      case "debug":
        logger.debug(format, arg);
        break;
      case "info":
        logger.info(format, arg);
        break;
      case "warn":
        logger.warn(format, arg);
        break;
      case "error":
        logger.error(format, arg);
        break;
      default:
        fail("Unexpected level " + level);
    }
  }

  /** Calls {@code logger.<level>(String, Object, Object)}. */
  private static void log(Logger logger, String level, String format, Object arg1, Object arg2) {
    switch (level) {
      case "debug":
        logger.debug(format, arg1, arg2);
        break;
      case "info":
        logger.info(format, arg1, arg2);
        break;
      case "warn":
        logger.warn(format, arg1, arg2);
        break;
      case "error":
        logger.error(format, arg1, arg2);
        break;
      default:
        fail("Unexpected level " + level);
    }
  }

  /** Calls {@code logger.<level>(String, Object...)}. */
  private static void logVarargs(Logger logger, String level, String format, Object... args) {
    switch (level) {
      case "debug":
        logger.debug(format, args);
        break;
      case "info":
        logger.info(format, args);
        break;
      case "warn":
        logger.warn(format, args);
        break;
      case "error":
        logger.error(format, args);
        break;
      default:
        fail("Unexpected level " + level);
    }
  }

  /** Calls {@code logger.<level>(String, Throwable)}. */
  private static void log(Logger logger, String level, String msg, Throwable throwable) {
    switch (level) {
      case "debug":
        logger.debug(msg, throwable);
        break;
      case "info":
        logger.info(msg, throwable);
        break;
      case "warn":
        logger.warn(msg, throwable);
        break;
      case "error":
        logger.error(msg, throwable);
        break;
      default:
        fail("Unexpected level " + level);
    }
  }

  /** Calls {@code logger.<level>(Marker, String, Object)}. */
  private static void log(Logger logger, String level, Marker marker, String format, Object arg) {
    switch (level) {
      case "debug":
        logger.debug(marker, format, arg);
        break;
      case "info":
        logger.info(marker, format, arg);
        break;
      case "warn":
        logger.warn(marker, format, arg);
        break;
      case "error":
        logger.error(marker, format, arg);
        break;
      default:
        fail("Unexpected level " + level);
    }
  }

  /** Calls {@code logger.<level>(Marker, String, Throwable)}. */
  private static void log(
      Logger logger, String level, Marker marker, String msg, Throwable throwable) {
    switch (level) {
      case "debug":
        logger.debug(marker, msg, throwable);
        break;
      case "info":
        logger.info(marker, msg, throwable);
        break;
      case "warn":
        logger.warn(marker, msg, throwable);
        break;
      case "error":
        logger.error(marker, msg, throwable);
        break;
      default:
        fail("Unexpected level " + level);
    }
  }
}

package datadog.trace.logging;

import static datadog.trace.logging.LogValidator.createValidator;
import static datadog.trace.logging.simplelogger.SLCompatSettings.Keys.DEFAULT_LOG_LEVEL;
import static datadog.trace.logging.simplelogger.SLCompatSettings.Keys.SHOW_THREAD_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.logging.ddlogger.DDLogger;
import datadog.trace.logging.ddlogger.DDLoggerFactory;
import datadog.trace.logging.simplelogger.SLCompatFactory;
import datadog.trace.logging.simplelogger.SLCompatSettings;
import java.io.PrintStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

class GlobalLogLevelSwitcherTest {

  @Test
  void testSuccessfulCreation() {
    // when
    Properties props = new Properties();
    props.setProperty(DEFAULT_LOG_LEVEL, "WARN");
    props.setProperty(SHOW_THREAD_NAME, "false");
    LogValidator globalValidator = createValidator(GlobalLogLevelSwitcher.class.getName());
    PrintStream printStream = new PrintStream(globalValidator.getOutputStream(), true);
    SLCompatSettings settings = new SLCompatSettings(props, null, printStream);
    DDLoggerFactory factory = new DDLoggerFactory(new SLCompatFactory(props, settings));
    Logger logger = factory.getLogger("foo.bar");
    LogValidator loggerValidator = globalValidator.withName("foo.bar");
    GlobalLogLevelSwitcher global = new GlobalLogLevelSwitcher(factory);

    // then
    globalValidator.nothing();
    logger.warn("check warn");
    loggerValidator.warn(true, "check warn");
    logger.debug("check debug");
    loggerValidator.nothing();
    global.switchLevel(LogLevel.DEBUG);
    logger.debug("check debug");
    loggerValidator.debug(true, "check debug");
    global.restore();
    logger.debug("check debug");
    loggerValidator.nothing();
    logger.warn("check warn");
    loggerValidator.warn(true, "check warn");
  }

  @Test
  void testNoopCreation() {
    // when
    Properties props = new Properties();
    props.setProperty(DEFAULT_LOG_LEVEL, "WARN");
    props.setProperty(SHOW_THREAD_NAME, "false");
    LogValidator globalValidator = createValidator(GlobalLogLevelSwitcher.class.getName());
    PrintStream printStream = new PrintStream(globalValidator.getOutputStream(), true);
    SLCompatSettings settings = new SLCompatSettings(props, null, printStream);
    LoggerFactory factory = new LoggerFactory(new SLCompatFactory(props, settings));
    Logger logger = factory.getLogger("foo.bar");
    LogValidator loggerValidator = globalValidator.withName("foo.bar");
    GlobalLogLevelSwitcher global = new GlobalLogLevelSwitcher(factory);

    // then
    globalValidator.error(true, "Unable to find global log level switcher, found LoggerFactory");
    logger.warn("check warn");
    loggerValidator.warn(true, "check warn");
    logger.debug("check debug");
    loggerValidator.nothing();
    global.switchLevel(LogLevel.DEBUG);
    logger.debug("check debug");
    loggerValidator.nothing();
    global.restore();
    logger.debug("check debug");
    loggerValidator.nothing();
    logger.warn("check warn");
    loggerValidator.warn(true, "check warn");
  }

  @Test
  void callGetToIncreaseCoverage() {
    // when
    LogLevelSwitcher global1 = GlobalLogLevelSwitcher.get();
    LogLevelSwitcher global2 = GlobalLogLevelSwitcher.get();

    // then
    assertEquals(global1, global2);
    assertNotNull(global1);
  }

  static class LoggerFactory implements ILoggerFactory {
    private final LoggerHelperFactory helperFactory;

    LoggerFactory(LoggerHelperFactory helperFactory) {
      this.helperFactory = helperFactory;
    }

    @Override
    public Logger getLogger(String name) {
      return new DDLogger(helperFactory.loggerHelperForName(name), name);
    }
  }
}

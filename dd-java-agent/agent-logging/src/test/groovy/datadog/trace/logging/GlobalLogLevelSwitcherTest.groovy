package datadog.trace.logging

import datadog.trace.logging.ddlogger.DDLogger
import datadog.trace.logging.ddlogger.DDLoggerFactory
import datadog.trace.logging.simplelogger.SLCompatFactory
import datadog.trace.logging.simplelogger.SLCompatSettings
import org.slf4j.ILoggerFactory
import org.slf4j.Logger

class GlobalLogLevelSwitcherTest extends LogValidatingSpecification {

  def "test successful creation"() {
    when:
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, "WARN")
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false")
    def globalValidator = createValidator(GlobalLogLevelSwitcher.name)
    def printStream = new PrintStream(globalValidator.outputStream, true)
    def settings = new SLCompatSettings(props, null, printStream)
    def factory = new DDLoggerFactory(new SLCompatFactory(props, settings))
    def logger = factory.getLogger("foo.bar")
    def loggerValidator = globalValidator.withName("foo.bar")
    def global = new GlobalLogLevelSwitcher(factory)

    then: {
      globalValidator.nothing()
      logger.warn("check warn")
      loggerValidator.warn(true, "check warn")
      logger.debug("check debug")
      loggerValidator.nothing()
      global.switchLevel(LogLevel.DEBUG)
      logger.debug("check debug")
      loggerValidator.debug(true, "check debug")
      global.restore()
      logger.debug("check debug")
      loggerValidator.nothing()
      logger.warn("check warn")
      loggerValidator.warn(true, "check warn")
    }
  }

  def "test noop creation"() {
    when:
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, "WARN")
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false")
    def globalValidator = createValidator(GlobalLogLevelSwitcher.name)
    def printStream = new PrintStream(globalValidator.outputStream, true)
    def settings = new SLCompatSettings(props, null, printStream)
    def factory = new LoggerFactory(new SLCompatFactory(props, settings))
    def logger = factory.getLogger("foo.bar")
    def loggerValidator = globalValidator.withName("foo.bar")
    def global = new GlobalLogLevelSwitcher(factory)

    then: {
      globalValidator.error(true, "Unable to find global log level switcher, found LoggerFactory")
      logger.warn("check warn")
      loggerValidator.warn(true, "check warn")
      logger.debug("check debug")
      loggerValidator.nothing()
      global.switchLevel(LogLevel.DEBUG)
      logger.debug("check debug")
      loggerValidator.nothing()
      global.restore()
      logger.debug("check debug")
      loggerValidator.nothing()
      logger.warn("check warn")
      loggerValidator.warn(true, "check warn")
    }
  }

  def "call get to increase coverage"() {
    when:
    def global1 = GlobalLogLevelSwitcher.get()
    def global2 = GlobalLogLevelSwitcher.get()

    then:
    global1 == global2
    global1 != null
  }

  static class LoggerFactory implements ILoggerFactory {
    private final LoggerHelperFactory helperFactory

    LoggerFactory(LoggerHelperFactory helperFactory) {
      this.helperFactory = helperFactory
    }

    @Override
    Logger getLogger(String name) {
      return new DDLogger(helperFactory.loggerHelperForName(name), name)
    }
  }
}

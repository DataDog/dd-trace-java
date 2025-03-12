package datadog.trace.logging.ddlogger

import datadog.slf4j.impl.StaticMarkerBinder
import datadog.trace.logging.LogLevel
import datadog.trace.logging.LogValidatingSpecification
import datadog.trace.logging.simplelogger.SLCompatFactory
import datadog.trace.logging.simplelogger.SLCompatSettings
import org.slf4j.Logger

import static datadog.trace.logging.simplelogger.SLCompatSettings.Names
import static datadog.trace.logging.simplelogger.SLCompatSettings.Keys
import static datadog.trace.logging.simplelogger.SLCompatSettings.Defaults

class DDLoggerTest extends LogValidatingSpecification {

  def marker = StaticMarkerBinder.getSingleton().markerFactory.getMarker("marker")

  void validateEnabled(Logger logger, boolean trace, boolean debug, boolean info, boolean warn, boolean error) {
    assert logger.isTraceEnabled() == trace
    assert logger.isTraceEnabled(marker) == trace

    assert logger.isDebugEnabled() == debug
    assert logger.isDebugEnabled(marker) == debug

    assert logger.isInfoEnabled() == info
    assert logger.isInfoEnabled(marker) == info

    assert logger.isWarnEnabled() == warn
    assert logger.isWarnEnabled(marker) == warn

    assert logger.isErrorEnabled() == error
    assert logger.isErrorEnabled(marker) == error
  }

  def "test enabled and log level switching"() {
    when:
    Properties props = new Properties()
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, level)
    def factory = new DDLoggerFactory(new SLCompatFactory(props))
    def logger = factory.getLogger("foo.bar")

    then:
    validateEnabled(logger, trace, debug, info, warn, error)
    factory.switchLevel(LogLevel.TRACE)
    validateEnabled(logger, true, true, true, true, true)
    factory.switchLevel(LogLevel.DEBUG)
    validateEnabled(logger, false, true, true, true, true)
    factory.switchLevel(LogLevel.INFO)
    validateEnabled(logger, false, false, true, true, true)
    factory.switchLevel(LogLevel.WARN)
    validateEnabled(logger, false, false, false, true, true)
    factory.switchLevel(LogLevel.ERROR)
    validateEnabled(logger, false, false, false, false, true)
    factory.switchLevel(LogLevel.OFF)
    validateEnabled(logger, false, false, false, false, false)
    factory.restore()
    validateEnabled(logger, trace, debug, info, warn, error)

    where:
    level   | trace | debug | info  | warn  | error
    "trace" | true  | true  | true  | true  | true
    "debug" | false | true  | true  | true  | true
    "info"  | false | false | true  | true  | true
    "warn"  | false | false | false | true  | true
    "error" | false | false | false | false | true
    "off"   | false | false | false | false | false
  }

  static String validateLogLine(OutputStream outputStream, String previous, boolean enabled, String level, String msg, String emsg) {
    def total = outputStream.toString()
    def current = total.substring(previous.length())
    def expected = ""
    if (enabled) {
      expected = "$level foo.bar - $msg\n"
      expected = emsg == null ? expected : "$expected${NoStackException.getName()}: $emsg\n"
    }
    assert current == expected
    return total
  }

  def "test logging"() {
    when:
    Properties props = new Properties()
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, level)
    props.setProperty(Keys.SHOW_THREAD_NAME, "false")
    def validator = createValidator("foo.bar")
    def printStream = new PrintStream(validator.outputStream, true)
    def settings = new SLCompatSettings(props, null, printStream)
    def factory = new DDLoggerFactory(new SLCompatFactory(props, settings))
    def logger = factory.getLogger("foo.bar")

    then: {
      // TRACE
      logger.trace("m1")
      validator.trace(trace, "m1")
      logger.trace("m1 {}", "a")
      validator.trace(trace, "m1 a")
      logger.trace("m1 {} {}", "b1", "b2")
      validator.trace(trace, "m1 b1 b2")
      logger.trace("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      validator.trace(trace, "m1 c1 c2 c3 c4")
      logger.trace("m1", exception("d"))
      validator.trace(trace, "m1", "d")
      logger.trace(marker, "m2")
      validator.trace(trace, marker, "m2")
      logger.trace(marker, "m2 {}", "e")
      validator.trace(trace, marker, "m2 e")
      logger.trace(marker, "m2 {} {}", "f2", "f1")
      validator.trace(trace, marker, "m2 f2 f1")
      logger.trace(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      validator.trace(trace, marker, "m2 g4 g3 g2 g1")
      logger.trace("m2", exception("h"))
      validator.trace(trace, "m2", "h")

      // DEBUG
      logger.debug("m1")
      validator.debug(debug, "m1")
      logger.debug("m1 {}", "a")
      validator.debug(debug, "m1 a")
      logger.debug("m1 {} {}", "b1", "b2")
      validator.debug(debug, "m1 b1 b2")
      logger.debug("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      validator.debug(debug, "m1 c1 c2 c3 c4")
      logger.debug("m1", exception("d"))
      validator.debug(debug, "m1", "d")
      logger.debug(marker, "m2")
      validator.debug(debug, marker, "m2")
      logger.debug(marker, "m2 {}", "e")
      validator.debug(debug, marker, "m2 e")
      logger.debug(marker, "m2 {} {}", "f2", "f1")
      validator.debug(debug, marker, "m2 f2 f1")
      logger.debug(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      validator.debug(debug, marker, "m2 g4 g3 g2 g1")
      logger.debug("m2", exception("h"))
      validator.debug(debug, "m2", "h")

      // INFO
      logger.info("m1")
      validator.info(info, "m1")
      logger.info("m1 {}", "a")
      validator.info(info, "m1 a")
      logger.info("m1 {} {}", "b1", "b2")
      validator.info(info, "m1 b1 b2")
      logger.info("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      validator.info(info, "m1 c1 c2 c3 c4")
      logger.info("m1", exception("d"))
      validator.info(info, "m1", "d")
      logger.info(marker, "m2")
      validator.info(info, marker, "m2")
      logger.info(marker, "m2 {}", "e")
      validator.info(info, marker, "m2 e")
      logger.info(marker, "m2 {} {}", "f2", "f1")
      validator.info(info, marker, "m2 f2 f1")
      logger.info(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      validator.info(info, marker, "m2 g4 g3 g2 g1")
      logger.info("m2", exception("h"))
      validator.info(info, "m2", "h")

      // WARN
      logger.warn("m1")
      validator.warn(warn, "m1")
      logger.warn("m1 {}", "a")
      validator.warn(warn, "m1 a")
      logger.warn("m1 {} {}", "b1", "b2")
      validator.warn(warn, "m1 b1 b2")
      logger.warn("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      validator.warn(warn, "m1 c1 c2 c3 c4")
      logger.warn("m1", exception("d"))
      validator.warn(warn, "m1", "d")
      logger.warn(marker, "m2")
      validator.warn(warn, marker, "m2")
      logger.warn(marker, "m2 {}", "e")
      validator.warn(warn, marker, "m2 e")
      logger.warn(marker, "m2 {} {}", "f2", "f1")
      validator.warn(warn, marker, "m2 f2 f1")
      logger.warn(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      validator.warn(warn, marker, "m2 g4 g3 g2 g1")
      logger.warn("m2", exception("h"))
      validator.warn(warn, "m2", "h")

      // ERROR
      logger.error("m1")
      validator.error(error, "m1")
      logger.error("m1 {}", "a")
      validator.error(error, "m1 a")
      logger.error("m1 {} {}", "b1", "b2")
      validator.error(error, "m1 b1 b2")
      logger.error("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      validator.error(error, "m1 c1 c2 c3 c4")
      logger.error("m1", exception("d"))
      validator.error(error, "m1", "d")
      logger.error(marker, "m2")
      validator.error(error, marker, "m2")
      logger.error(marker, "m2 {}", "e")
      validator.error(error, marker, "m2 e")
      logger.error(marker, "m2 {} {}", "f2", "f1")
      validator.error(error, marker, "m2 f2 f1")
      logger.error(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      validator.error(error, marker, "m2 g4 g3 g2 g1")
      logger.error("m2", exception("h"))
      validator.error(error, "m2", "h")
    }

    where:
    level   | trace | debug | info  | warn  | error | off
    "trace" | true  | true  | true  | true  | true  | true
    "debug" | false | true  | true  | true  | true  | true
    "info"  | false | false | true  | true  | true  | true
    "warn"  | false | false | false | true  | true  | true
    "error" | false | false | false | false | true  | true
    "off"   | false | false | false | false | false | true
  }

  def "test logging with an embedded exception in the message"() {
    setup:
    Properties props = new Properties()
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, "$level")
    props.setProperty(Keys.EMBED_EXCEPTION, "true")
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def settings = new SLCompatSettings(props, null, printStream)
    def factory = new DDLoggerFactory(new SLCompatFactory(props, settings))
    def logger = factory.getLogger("foo")

    when:
    try {
      throw new IOException("wrong")
    } catch(Exception exception) {
      switch (level) {
        case LogLevel.TRACE:
          logger.trace("log", exception)
          break
        case LogLevel.DEBUG:
          logger.debug("log", exception)
          break
        case LogLevel.INFO:
          logger.info("log", exception)
          break
        case LogLevel.WARN:
          logger.warn("log", exception)
          break
        case LogLevel.ERROR:
          logger.error("log", exception)
          break
        default:
          logger.error("Weird Level $level")
      }
    }

    then:
    outputStream.toString() ==~ /^.* $level foo - log \[exception:java\.io\.IOException: wrong\. at .*\]\n$/

    where:
    level << LogLevel.values().toList().take(5) // remove LogLevel.OFF
  }

  def "test logging with an embedded exception in the message and varargs"() {
    setup:
    Properties props = new Properties()
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, "$level")
    props.setProperty(Keys.EMBED_EXCEPTION, "true")
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def settings = new SLCompatSettings(props, null, printStream)
    def factory = new DDLoggerFactory(new SLCompatFactory(props, settings))
    def logger = factory.getLogger("foo")

    when:
    try {
      throw new IOException("wrong")
    } catch(Exception exception) {
      logVarargs(logger, level, "log {}", "some", exception)
    }

    then:
    outputStream.toString() ==~ /^.* $level foo - log some more \[exception:java\.io\.IOException: wrong\. at .*\]\n$/

    where:
    level << LogLevel.values().toList().take(5) // remove LogLevel.OFF
  }

  void logVarargs(DDLogger logger, LogLevel level, String format, Object... arguments) {
    String fmt = format + " more"
    switch (level) {
      case LogLevel.TRACE:
        logger.trace(fmt, arguments)
        break
      case LogLevel.DEBUG:
        logger.debug(fmt, arguments)
        break
      case LogLevel.INFO:
        logger.info(fmt, arguments)
        break
      case LogLevel.WARN:
        logger.warn(fmt, arguments)
        break
      case LogLevel.ERROR:
        logger.error(fmt, arguments)
        break
      default:
        logger.error("Weird Level $level")
    }
  }

  def "test log output to a file"() {
    setup:
    def dir = File.createTempDir()
    def file = new File(dir, "log")
    def props = new Properties()
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, "DEBUG")
    props.setProperty(Keys.SHOW_THREAD_NAME, "false")
    props.setProperty(Keys.LOG_FILE, file.getAbsolutePath())
    def settings = new SLCompatSettings(props)
    def factory = new DDLoggerFactory(new SLCompatFactory(props))
    def logger = factory.getLogger("bar.baz")
    logger.trace("test trace")
    logger.debug("test debug")
    logger.info("test info")
    logger.warn("test warn")
    logger.error("test error")
    def scanner = new Scanner(file)

    expect:
    file.exists()
    scanner.hasNextLine()
    // We should not log at trace level
    scanner.nextLine() == "DEBUG bar.baz - test debug"
    scanner.nextLine() == "INFO bar.baz - test info"
    scanner.nextLine() == "WARN bar.baz - test warn"
    scanner.nextLine() == "ERROR bar.baz - test error"

    cleanup:
    scanner.close()
    settings.printStream.close()
    dir.listFiles().each {
      it.delete()
    }
    dir.delete()
  }

  def "test logger settings description"() {
    setup:
    def props = new Properties()
    props.setProperty(Keys.DEFAULT_LOG_LEVEL, level.toString())
    props.setProperty(Keys.SHOW_THREAD_NAME, "false")
    props.setProperty(Keys.SHOW_DATE_TIME, "true")
    if (warn) {
      props.setProperty(Keys.WARN_LEVEL_STRING, warn)
    }
    def settings = new SLCompatSettings(props)
    def factory = new SLCompatFactory(props, settings)

    expect:
    factory.settingsDescription == [
      (Names.WARN_LEVEL_STRING): expectedWarn,
      (Names.LEVEL_IN_BRACKETS): Defaults.LEVEL_IN_BRACKETS,
      (Names.LOG_FILE): Defaults.LOG_FILE,
      (Names.SHOW_LOG_NAME): Defaults.SHOW_LOG_NAME,
      (Names.SHOW_SHORT_LOG_NAME): Defaults.SHOW_SHORT_LOG_NAME,
      (Names.SHOW_THREAD_NAME): false,
      (Names.SHOW_DATE_TIME): true,
      (Names.JSON_ENABLED): Defaults.JSON_ENABLED,
      (Names.DATE_TIME_FORMAT): "relative",
      (Names.DEFAULT_LOG_LEVEL): expectedLevel,
      (Names.EMBED_EXCEPTION): Defaults.EMBED_EXCEPTION,
      (Names.CONFIGURATION_FILE): Defaults.CONFIGURATION_FILE,
    ]

    where:
    level          | warn  | expectedLevel | expectedWarn
    LogLevel.TRACE | null  | "TRACE"       | "WARN"
    LogLevel.ERROR | "WRN" | "ERROR"       | "WRN"
  }
}

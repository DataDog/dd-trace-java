package datadog.trace.logging

import datadog.slf4j.impl.StaticMarkerBinder
import datadog.trace.logging.simplelogger.SLCompatFactory
import datadog.trace.logging.simplelogger.SLCompatSettings
import spock.lang.Specification

class DDLoggerTest extends Specification {

  private class NoStackException extends Exception {
    NoStackException(String message) {
      super(message, null, false, false)
    }
  }

  def marker = StaticMarkerBinder.getSingleton().markerFactory.getMarker("marker")

  def "test enabled"() {
    when:
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, level)
    def factory = new SLCompatFactory(props)
    def logger = new DDLogger(factory, "foo.bar")

    then:
    logger.isTraceEnabled() == trace
    logger.isTraceEnabled(marker) == trace

    logger.isDebugEnabled() == debug
    logger.isDebugEnabled(marker) == debug

    logger.isInfoEnabled() == info
    logger.isInfoEnabled(marker) == info

    logger.isWarnEnabled() == warn
    logger.isWarnEnabled(marker) == warn

    logger.isErrorEnabled() == error
    logger.isErrorEnabled(marker) == error

    where:
    level   | trace | debug | info  | warn  | error | off
    "trace" | true  | true  | true  | true  | true  | true
    "debug" | false | true  | true  | true  | true  | true
    "info"  | false | false | true  | true  | true  | true
    "warn"  | false | false | false | true  | true  | true
    "error" | false | false | false | false | true  | true
    "off"   | false | false | false | false | false | true
  }

  String validateLogLine(OutputStream outputStream, String previous, boolean enabled, String level, String msg, String emsg) {
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
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, level)
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false")
    def os = new ByteArrayOutputStream()
    def printStream = new PrintStream(os, true)
    def settings = new SLCompatSettings(props, null, printStream)
    def factory = new SLCompatFactory(props, settings)
    def logger = new DDLogger(factory, "foo.bar")

    then:
    {
      def logged = ""
      // TRACE
      logger.trace("m1")
      logged = validateLogLine(os, logged, trace, "TRACE", "m1", null)
      logger.trace("m1 {}", "a")
      logged = validateLogLine(os, logged, trace, "TRACE", "m1 a", null)
      logger.trace("m1 {} {}", "b1", "b2")
      logged = validateLogLine(os, logged, trace, "TRACE", "m1 b1 b2", null)
      logger.trace("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      logged = validateLogLine(os, logged, trace, "TRACE", "m1 c1 c2 c3 c4", null)
      logger.trace("m1", new NoStackException("d"))
      logged = validateLogLine(os, logged, trace, "TRACE", "m1", "d")
      logger.trace(marker, "m2")
      logged = validateLogLine(os, logged, trace, "TRACE", "m2", null)
      logger.trace(marker, "m2 {}", "e")
      logged = validateLogLine(os, logged, trace, "TRACE", "m2 e", null)
      logger.trace(marker, "m2 {} {}", "f2", "f1")
      logged = validateLogLine(os, logged, trace, "TRACE", "m2 f2 f1", null)
      logger.trace(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      logged = validateLogLine(os, logged, trace, "TRACE", "m2 g4 g3 g2 g1", null)
      logger.trace("m2", new NoStackException("h"))
      logged = validateLogLine(os, logged, trace, "TRACE", "m2", "h")

      // DEBUG
      logger.debug("m1")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m1", null)
      logger.debug("m1 {}", "a")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m1 a", null)
      logger.debug("m1 {} {}", "b1", "b2")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m1 b1 b2", null)
      logger.debug("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m1 c1 c2 c3 c4", null)
      logger.debug("m1", new NoStackException("d"))
      logged = validateLogLine(os, logged, debug, "DEBUG", "m1", "d")
      logger.debug(marker, "m2")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m2", null)
      logger.debug(marker, "m2 {}", "e")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m2 e", null)
      logger.debug(marker, "m2 {} {}", "f2", "f1")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m2 f2 f1", null)
      logger.debug(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      logged = validateLogLine(os, logged, debug, "DEBUG", "m2 g4 g3 g2 g1", null)
      logger.debug("m2", new NoStackException("h"))
      logged = validateLogLine(os, logged, debug, "DEBUG", "m2", "h")

      // INFO
      logger.info("m1")
      logged = validateLogLine(os, logged, info, "INFO", "m1", null)
      logger.info("m1 {}", "a")
      logged = validateLogLine(os, logged, info, "INFO", "m1 a", null)
      logger.info("m1 {} {}", "b1", "b2")
      logged = validateLogLine(os, logged, info, "INFO", "m1 b1 b2", null)
      logger.info("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      logged = validateLogLine(os, logged, info, "INFO", "m1 c1 c2 c3 c4", null)
      logger.info("m1", new NoStackException("d"))
      logged = validateLogLine(os, logged, info, "INFO", "m1", "d")
      logger.info(marker, "m2")
      logged = validateLogLine(os, logged, info, "INFO", "m2", null)
      logger.info(marker, "m2 {}", "e")
      logged = validateLogLine(os, logged, info, "INFO", "m2 e", null)
      logger.info(marker, "m2 {} {}", "f2", "f1")
      logged = validateLogLine(os, logged, info, "INFO", "m2 f2 f1", null)
      logger.info(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      logged = validateLogLine(os, logged, info, "INFO", "m2 g4 g3 g2 g1", null)
      logger.info("m2", new NoStackException("h"))
      logged = validateLogLine(os, logged, info, "INFO", "m2", "h")

      // WARN
      logger.warn("m1")
      logged = validateLogLine(os, logged, warn, "WARN", "m1", null)
      logger.warn("m1 {}", "a")
      logged = validateLogLine(os, logged, warn, "WARN", "m1 a", null)
      logger.warn("m1 {} {}", "b1", "b2")
      logged = validateLogLine(os, logged, warn, "WARN", "m1 b1 b2", null)
      logger.warn("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      logged = validateLogLine(os, logged, warn, "WARN", "m1 c1 c2 c3 c4", null)
      logger.warn("m1", new NoStackException("d"))
      logged = validateLogLine(os, logged, warn, "WARN", "m1", "d")
      logger.warn(marker, "m2")
      logged = validateLogLine(os, logged, warn, "WARN", "m2", null)
      logger.warn(marker, "m2 {}", "e")
      logged = validateLogLine(os, logged, warn, "WARN", "m2 e", null)
      logger.warn(marker, "m2 {} {}", "f2", "f1")
      logged = validateLogLine(os, logged, warn, "WARN", "m2 f2 f1", null)
      logger.warn(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      logged = validateLogLine(os, logged, warn, "WARN", "m2 g4 g3 g2 g1", null)
      logger.warn("m2", new NoStackException("h"))
      logged = validateLogLine(os, logged, warn, "WARN", "m2", "h")

      // ERROR
      logger.error("m1")
      logged = validateLogLine(os, logged, error, "ERROR", "m1", null)
      logger.error("m1 {}", "a")
      logged = validateLogLine(os, logged, error, "ERROR", "m1 a", null)
      logger.error("m1 {} {}", "b1", "b2")
      logged = validateLogLine(os, logged, error, "ERROR", "m1 b1 b2", null)
      logger.error("m1 {} {} {} {}", "c1", "c2", "c3", "c4")
      logged = validateLogLine(os, logged, error, "ERROR", "m1 c1 c2 c3 c4", null)
      logger.error("m1", new NoStackException("d"))
      logged = validateLogLine(os, logged, error, "ERROR", "m1", "d")
      logger.error(marker, "m2")
      logged = validateLogLine(os, logged, error, "ERROR", "m2", null)
      logger.error(marker, "m2 {}", "e")
      logged = validateLogLine(os, logged, error, "ERROR", "m2 e", null)
      logger.error(marker, "m2 {} {}", "f2", "f1")
      logged = validateLogLine(os, logged, error, "ERROR", "m2 f2 f1", null)
      logger.error(marker, "m2 {} {} {} {}", "g4", "g3", "g2", "g1")
      logged = validateLogLine(os, logged, error, "ERROR", "m2 g4 g3 g2 g1", null)
      logger.error("m2", new NoStackException("h"))
      logged = validateLogLine(os, logged, error, "ERROR", "m2", "h")
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

}

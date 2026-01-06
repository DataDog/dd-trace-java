package datadog.trace.relocate.api

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY

import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger

class IOLoggerTest extends DDSpecification {
  final response = new IOLogger.Response(404, "Not Found",
  "The thing you were looking for does not exist")
  final exception = new RuntimeException("Something went wrong!")

  Logger log = Mock(Logger)
  RatelimitedLogger rateLimitedLogger = Mock(RatelimitedLogger)
  IOLogger ioLogger = new IOLogger(log, rateLimitedLogger)

  def "Success - Debug level"() {
    setup:
    log.isDebugEnabled() >> true
    log.isInfoEnabled() >> true

    when:
    ioLogger.success("test {}", "message")

    then:
    1 * log.debug(EXCLUDE_TELEMETRY, "test {}", "message")
  }

  def "Success - Info level"() {
    setup:
    log.isDebugEnabled() >> false
    log.isInfoEnabled() >> true

    when:
    ioLogger.success("test {}", "message")

    then:
    0 * log.debug(_)
    0 * log.info(_)
  }

  def "Error - Debug level - Message"() {
    setup:
    log.isDebugEnabled() >> true
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message")

    then:
    1 * log.debug(EXCLUDE_TELEMETRY, "test message")
  }

  def "Error - Debug level - Response"() {
    setup:
    log.isDebugEnabled() >> true
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message", response)

    then:
    1 * log.debug(
      EXCLUDE_TELEMETRY,
      _,
      "test message",
      404,
      "Not Found",
      "The thing you were looking for does not exist"
      )
  }

  def "Error - Debug level - Exception"() {
    setup:
    log.isDebugEnabled() >> true
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message", exception)

    then:
    1 * log.debug(EXCLUDE_TELEMETRY,
      "test message",
      exception
      )
  }

  def "Error - Info level - Message"() {
    setup:
    log.isDebugEnabled() >> false
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message")

    then:
    1 * rateLimitedLogger.warn("test message")
  }

  def "Error - Info level - Response"() {
    setup:
    log.isDebugEnabled() >> false
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message", response)

    then:
    1 * rateLimitedLogger.warn(
      EXCLUDE_TELEMETRY,
      _,
      "test message",
      404,
      "Not Found"
      )
  }

  def "Error - Info level - Exception"() {
    setup:
    log.isDebugEnabled() >> false
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message", exception)

    then:
    1 * rateLimitedLogger.warn(
      EXCLUDE_TELEMETRY,
      _,
      "test message",
      "java.lang.RuntimeException",
      "Something went wrong!"
      )
  }

  def "Logged Error Then Success - Info level"() {
    setup:
    log.isDebugEnabled() >> false
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message")
    ioLogger.success("very successful")
    ioLogger.success("very successful again")

    then:
    1 * rateLimitedLogger.warn("test message") >> true
    1 * log.info(EXCLUDE_TELEMETRY,"very successful")
    0 * log.info(EXCLUDE_TELEMETRY,"very successful again")
  }

  def "Unlogged Error Then Success - Info level"() {
    setup:
    log.isDebugEnabled() >> false
    log.isInfoEnabled() >> true

    when:
    ioLogger.error("test message")
    ioLogger.success("very successful")
    ioLogger.success("very successful again")

    then:
    1 * rateLimitedLogger.warn("test message") >> false
    0 * log.info("very successful")
    0 * log.info("very successful again")
  }
}

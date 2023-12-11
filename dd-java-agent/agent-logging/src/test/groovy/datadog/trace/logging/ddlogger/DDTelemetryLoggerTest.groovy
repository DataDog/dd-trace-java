package datadog.trace.logging.ddlogger

import datadog.trace.api.telemetry.LogCollector
import datadog.trace.logging.LogValidatingSpecification
import datadog.trace.logging.simplelogger.SLCompatFactory
import datadog.trace.logging.simplelogger.SLCompatSettings

class DDTelemetryLoggerTest extends LogValidatingSpecification {
  def "test logging with log collector"() {
    setup:
    def name = "foo.bar"
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, "debug")
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false")
    def settings = new SLCompatSettings(props)
    def factory = new SLCompatFactory(props, settings)
    def logger = new DDTelemetryLogger(factory.loggerHelperForName(name), name)

    when:
    logger.debug("debug message {}", 42, new Exception())
    def collection = LogCollector.get().drain()

    then:
    collection.size() == 1
    collection[0].message() == 'debug message {}'

    when:
    logger.warn(LogCollector.SEND_TELEMETRY, "warning message {}", 42)
    collection = LogCollector.get().drain()

    then:
    collection.size() == 1
    collection[0].message() == 'warning message {}'

    when:
    logger.error(LogCollector.SEND_TELEMETRY, "plain error message")
    collection = LogCollector.get().drain()

    then:
    collection.size() == 1
    collection[0].message() == 'plain error message'

    when:
    logger.debug(LogCollector.SEND_TELEMETRY, null)
    logger.error(null, new Exception())
    logger.warn("warn message", null)
    collection = LogCollector.get().drain()

    then:
    collection.size() == 0
  }
}

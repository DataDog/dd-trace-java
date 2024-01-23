package datadog.trace.logging.ddlogger

import datadog.trace.api.telemetry.LogCollector
import datadog.trace.logging.LogValidatingSpecification
import datadog.trace.logging.simplelogger.SLCompatFactory
import datadog.trace.logging.simplelogger.SLCompatSettings

class DDTelemetryLoggerTest extends LogValidatingSpecification {

  void setup() {
    LogCollector.get().drain()
  }

  void cleanup() {
    LogCollector.get().drain()
  }

  static List<String> allLogLevels = ["debug", "info", "warn", "error"]

  DDLogger createLogger(final String level) {
    def name = "foo.bar"
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, level)
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false")
    def settings = new SLCompatSettings(props)
    def factory = new SLCompatFactory(props, settings)
    return new DDTelemetryLogger(factory.loggerHelperForName(name), name)
  }

  void 'do not log #call(format, obj) call with level #level'() {
    given:
    def format = "msg {}"
    def arg = 42
    def logger = createLogger(level)

    when:
    logger."$call"(format, arg)
    def collection = LogCollector.get().drain()

    then:
    collection.isEmpty()

    where:
    call << allLogLevels
    level << allLogLevels
  }

  void 'do not log #call(format, obj, obj, obj) call with level #level'() {
    given:
    def format = "msg {} {} {}"
    def arg = 42
    def logger = createLogger(level)

    when:
    logger."$call"(format, arg, arg, arg)
    def collection = LogCollector.get().drain()

    then:
    collection.isEmpty()

    where:
    call << allLogLevels
    level << allLogLevels
  }

  void 'log #call(SEND_TELEMETRY, format, obj) call with level #level'() {
    given:
    def format = "msg {}"
    def arg = 42
    def logger = createLogger(level)

    when:
    logger."$call"(LogCollector.SEND_TELEMETRY, format, arg)
    def collection = LogCollector.get().drain()

    then:
    collection.size() == 1
    collection[0].message == format

    where:
    call << allLogLevels
    level << allLogLevels
  }

  void 'log #call(msg, throwable) call with level #level'() {
    given:
    def format = "msg"
    def t = new Exception()
    def logger = createLogger(level)

    when:
    logger."$call"(format, t)
    def collection = LogCollector.get().drain()

    then:
    collection.size() == 1
    collection[0].message == format

    where:
    call << allLogLevels
    level << allLogLevels
  }

  void 'log #call(msg, obj, throwable) call with level #level'() {
    given:
    def format = "msg {}"
    def arg = 42
    def t = new Exception()
    def logger = createLogger(level)

    when:
    logger."$call"(format, arg, t)
    def collection = LogCollector.get().drain()

    then:
    collection.size() == 1
    collection[0].message == format

    where:
    call << allLogLevels
    level << allLogLevels
  }

  void 'log #call(msg, obj, obj, throwable) call with level #level'() {
    given:
    def format = "msg {}"
    def arg = 42
    def t = new Exception()
    def logger = createLogger(level)

    when:
    logger."$call"(format, arg, arg, t)
    def collection = LogCollector.get().drain()

    then:
    collection.size() == 1
    collection[0].message == format

    where:
    call << allLogLevels
    level << allLogLevels
  }
}

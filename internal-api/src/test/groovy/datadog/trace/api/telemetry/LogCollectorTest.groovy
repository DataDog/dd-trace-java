package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

class LogCollectorTest extends DDSpecification {

  void "tracer time is set"() {
    setup:
    def logCollector = new LogCollector(1)

    when:
    logCollector.addLogMessage("ERROR", "Message 1", null)

    then:
    def log = logCollector.drain().toList().get(0)
    def ts = log.timestamp
    ts > 0L
    // Check tracer time is not in millis
    ts < 1706529524286L
  }

  void "limit log messages in LogCollector"() {
    setup:
    def logCollector = new LogCollector(3)

    when:
    logCollector.addLogMessage("ERROR", "Message 1", null)
    logCollector.addLogMessage("ERROR", "Message 2", null)
    logCollector.addLogMessage("ERROR", "Message 3", null)
    logCollector.addLogMessage("ERROR", "Message 4", null)

    then:
    logCollector.rawLogMessages.size() == 3
  }

  void "grouping messages in LogCollector"() {
    when:
    LogCollector.get().addLogMessage("ERROR", "First Message", null)
    LogCollector.get().addLogMessage("ERROR", "Second Message", null)
    LogCollector.get().addLogMessage("ERROR", "Third Message", null)
    LogCollector.get().addLogMessage("ERROR", "Forth Message", null)
    LogCollector.get().addLogMessage("ERROR", "Second Message", null)
    LogCollector.get().addLogMessage("ERROR", "Third Message", null)
    LogCollector.get().addLogMessage("ERROR", "Forth Message", null)
    LogCollector.get().addLogMessage("ERROR", "Third Message", null)
    LogCollector.get().addLogMessage("ERROR", "Forth Message", null)
    LogCollector.get().addLogMessage("ERROR", "Forth Message", null)

    then:
    def list = LogCollector.get().drain()
    list.size() == 4
    listContains(list, 'ERROR', "First Message", null, 1)
    listContains(list, 'ERROR', "Second Message", null, 2)
    listContains(list, 'ERROR', "Third Message", null,3)
    listContains(list, 'ERROR', "Forth Message", null, 4)
  }

  boolean listContains(Collection<LogCollector.RawLogMessage> list, String logLevel, String message, Throwable t, int count) {
    for (final def logMsg in list) {
      if (logMsg.logLevel == logLevel && logMsg.message == message && logMsg.throwable == t && logMsg.count == count) {
        return true
      }
    }
    return false
  }
}

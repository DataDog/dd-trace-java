package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

class LogCollectorTest extends DDSpecification {

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
    listContains(list, 'ERROR', "First Message", null)
    listContains(list, 'ERROR', "Second Message, {2} additional messages skipped", null)
    listContains(list, 'ERROR', "Third Message, {3} additional messages skipped", null)
    listContains(list, 'ERROR', "Forth Message, {4} additional messages skipped", null)
  }

  boolean listContains(Collection<LogCollector.RawLogMessage> list, String logLevel, String message, Throwable t) {
    for (final def logMsg in list) {
      if (logMsg.logLevel == logLevel && logMsg.message() == message && logMsg.throwable == t) {
        return true
      }
    }
    return false
  }
}

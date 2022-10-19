package datadog.trace.api

import datadog.trace.api.telemetry.LogCollector
import spock.lang.Specification

class LogCollectorTest  extends Specification {

  def 'Log Collector Test'() {
    setup:
    LogCollector.get().setEnabled(true)
    def messageList = LogCollector.get().drain()

    when:
    try {
      LogCollectorExceptionThrower.throwException("Exception Message")
    }
    catch (Exception e){
      LogCollector.get().addLogEntry("msg", "DEBUG", new Exception("EXception Message 2"), null, null, null, null)
      LogCollector.get().addLogEntry(null, "DEBUG", null, "here {} are", "we", null, null)
      LogCollector.get().addLogEntry("msg", "DEBUG", e, "{} {}", "1", "2", null)
      String[] args = ["A", "B", "C"]
      LogCollector.get().addLogEntry(null, "DEBUG", null, "{}", null,null, args, e)

      for (int i = 0; i < LogCollector.MAX_ENTRIES; i++) {
        LogCollector.get().addLogEntry("msg " + i, "DEBUG", null, null, null, null, null)
      }
      messageList = LogCollector.get().drain()
    }

    then:
    messageList.size() == LogCollector.MAX_ENTRIES + 1
    messageList.get(0).getMessage() == "msg"
    !messageList.get(0).getStackTrace().contains("Exception Message 2")
    messageList.get(1).getMessage() == "here {} are"
    messageList.get(1).getLevel() == "DEBUG"
    messageList.get(3).getMessage() == "[A, B, C]"
    messageList.get(3).getStackTrace().contains("Exception Message")
    messageList.get(3).getTags() == null
    messageList.get(4).toString().contains("stack_trace")
  }
}

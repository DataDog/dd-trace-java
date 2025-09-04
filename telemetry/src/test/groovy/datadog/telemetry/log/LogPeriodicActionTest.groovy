package datadog.telemetry.log

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.LogMessageLevel
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.test.util.DDSpecification

class LogPeriodicActionTest extends DDSpecification {
  LogPeriodicAction periodicAction = new LogPeriodicAction()
  TelemetryService telemetryService = Mock()

  @Override
  void setup(){
    injectSysConfig("dd.instrumentation.telemetry.debug", "true")
    LogCollector.get().drain()
  }

  @Override
  void cleanup(){
    LogCollector.get().drain()
  }

  void 'log with tags'() {
    LogMessage logMessage

    when:
    LogCollector.get().addLogMessage('ERROR', "test", null, 'tag1:value1,tag2:value2')
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getLevel() == LogMessageLevel.ERROR
    logMessage.getMessage() == 'test'
    logMessage.getTags() == 'tag1:value1,tag2:value2'
  }

  void 'log with datadog throwable'() {
    LogMessage logMessage

    given:
    final t = throwable("exception", stacktrace(frame("datadog.MyClass")))

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() == "${MutableException.canonicalName}\n" +
      "  at datadog.MyClass.method(file:42)\n"
  }

  void 'log with non-datadog throwable'() {
    LogMessage logMessage

    given:
    final t = throwable("exception", stacktrace(frame("java.MyClass")))

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() == "${MutableException.canonicalName}\n" +
      "  at java.MyClass.method(file:42)\n"
  }

  void 'log with datadog throwable without stacktrace'() {
    LogMessage logMessage

    given:
    final t = throwable("exception", [] as StackTraceElement[])

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() == "${MutableException.canonicalName}\n"
  }

  void 'deduplication of log messages without exception'() {
    LogMessage logMessage

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", null)
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", null)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getCount() == 2
  }

  void 'deduplication of log messages with exception'() {
    LogMessage logMessage

    given:
    final t1 = throwable("exception", stacktrace(frame("datadog.MyClass")))
    final t2 = throwable("exception", stacktrace(frame("datadog.MyClass")))

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t1)
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t2)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() != null
    logMessage.getCount() == 2
  }

  void 'stacktrace redaction'() {
    LogMessage logMessage

    given:
    final t = throwable("exception", stacktrace(
      frame(""),
      frame("java.MyClass"),
      frame("mycorp.MyClass"),
      frame("datadog.MyClass"),
      frame("mycorp.MyClass"),
      frame("mycorp.MyClass"),
      ))

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() == "${MutableException.canonicalName}\n" +
      "  at (redacted)\n" +
      "  at java.MyClass.method(file:42)\n" +
      "  at (redacted)\n" +
      "  at datadog.MyClass.method(file:42)\n" +
      "  at (redacted: 2 frames)\n"
  }

  void 'stacktrace with multiple frames and common frames'() {
    LogMessage logMessage

    given:
    final t = throwable("exception", stacktrace(
      frame(""),
      frame("datadog.MyClass"),
      frame("mycorp.MyClass"),
      frame("mycorp.MyClass"),
      ), throwable("exception 2", stacktrace(
      frame("java.MyClass"),
      frame("mycorp.MyClass"),
      frame("datadog.MyClass"),
      frame("mycorp.MyClass"),
      frame("mycorp.MyClass"),
      )))

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() == "${MutableException.canonicalName}\n" +
      "  at (redacted)\n" +
      "  at datadog.MyClass.method(file:42)\n" +
      "  at (redacted: 2 frames)\n" +
      "Caused by: ${MutableException.canonicalName}\n" +
      "  at java.MyClass.method(file:42)\n" +
      "  at (redacted)\n" +
      "  ... 3 more\n"
  }

  void 'stacktrace with common frames only'() {
    LogMessage logMessage

    given:
    final t = throwable("exception", stacktrace(
      frame("java.MyClass"),
      frame("mycorp.MyClass"),
      frame("datadog.MyClass"),
      frame("mycorp.MyClass"),
      frame("mycorp.MyClass"),
      ), throwable("exception 2", stacktrace(
      frame("java.MyClass"),
      frame("mycorp.MyClass"),
      frame("datadog.MyClass"),
      frame("mycorp.MyClass"),
      frame("mycorp.MyClass"),
      ), throwable("exception 3", stacktrace(
      frame("java.MyClass"),
      frame("mycorp.MyClass"),
      frame("datadog.MyClass"),
      frame("mycorp.MyClass"),
      frame("mycorp.MyClass"),
      ))))

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() == "${MutableException.canonicalName}\n" +
      "  at java.MyClass.method(file:42)\n" +
      "  at (redacted)\n" +
      "  at datadog.MyClass.method(file:42)\n" +
      "  at (redacted: 2 frames)\n" +
      "Caused by: ${MutableException.canonicalName}\n" +
      "  ... 5 more\n" +
      "Caused by: ${MutableException.canonicalName}\n" +
      "  ... 5 more\n"
  }

  void 'stacktrace without common frames'() {
    LogMessage logMessage

    given:
    final t = throwable("exception", stacktrace(
      frame("java.MyClass"),
      frame("mycorp.MyClass"),
      frame("datadog.MyClass"),
      frame("mycorp.MyClass"),
      frame("mycorp.MyClass"),
      ), throwable("exception 2", stacktrace(
      frame("java.MyClass"),
      frame("org.datadog.Test"),
      frame("io.DataTest"),
      frame("dd.MainClass"),
      )))

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", t)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() == "${MutableException.canonicalName}\n" +
      "  at java.MyClass.method(file:42)\n" +
      "  at (redacted)\n" +
      "  at datadog.MyClass.method(file:42)\n" +
      "  at (redacted: 2 frames)\n" +
      "Caused by: ${MutableException.canonicalName}\n" +
      "  at java.MyClass.method(file:42)\n" +
      "  at (redacted: 3 frames)\n"
  }

  static class MutableException extends Exception {
    MutableException(String message, Throwable cause) {
      super(message, cause, true, true)
    }
  }

  static Throwable throwable(String message, StackTraceElement[] stacktrace) {
    final MutableException t = new MutableException(message, null)
    t.setStackTrace(stacktrace)
    return t
  }

  static Throwable throwable(String message, StackTraceElement[] stacktrace, Throwable cause) {
    final MutableException t = new MutableException(message, cause)
    t.setStackTrace(stacktrace)
    return t
  }

  static StackTraceElement[] stacktrace(StackTraceElement... frames) {
    return frames
  }

  static StackTraceElement frame(String className, String methodName = "method", String fileName = "file", int lineNumber = 42) {
    return new StackTraceElement(className, methodName, fileName, lineNumber)
  }
}

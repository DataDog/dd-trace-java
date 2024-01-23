package datadog.telemetry.log

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.LogMessageLevel
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.test.util.DDSpecification

class LogPeriodicActionTest extends DDSpecification {
  LogPeriodicAction periodicAction = new LogPeriodicAction()
  TelemetryService telemetryService = Mock()

  List<Throwable> throwablesSameStacktrace

  @Override
  void setup(){
    injectSysConfig("dd.instrumentation.telemetry.debug", "true")
    LogCollector.get().drain()

    // Initialize multiple throwables with the same stacktrace
    throwablesSameStacktrace = new ArrayList<>()
    for (int i = 0; i < 2; i++) {
      try {
        ExceptionHelper.throwExceptionFromDatadogCode("error message")
      } catch (Exception e) {
        throwablesSameStacktrace[i] = e
      }
    }
  }

  @Override
  void cleanup(){
    LogCollector.get().drain()
  }

  void 'push exception into the telemetry service'() {
    setup:
    try {
      ExceptionHelper.throwExceptionFromDatadogCode(null)
    } catch (Exception e) {
      LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", e)
    }

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage( { LogMessage logMessage ->
      logMessage.getMessage() == 'test'
    } )
    0 * _._
  }

  void 'push exception (without stacktrace) into the telemetry service'() {
    setup:
    try {
      ExceptionHelper.throwExceptionFromDatadogCodeWithoutStacktrace(null)
    } catch (Exception e) {
      LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", e)
    }

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage( { LogMessage logMessage ->
      logMessage.getMessage() == 'test'
    } )
    0 * _._
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

    when:
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", throwablesSameStacktrace[0])
    LogCollector.get().addLogMessage(LogMessageLevel.ERROR.toString(), "test", throwablesSameStacktrace[1])
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_) >> { args -> logMessage = args[0] }
    0 * _
    logMessage.getMessage() == 'test'
    logMessage.getStackTrace() != null
    logMessage.getCount() == 2
  }
}

package datadog.telemetry.log

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.LogMessageLevel
import datadog.trace.api.LogCollector
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
}

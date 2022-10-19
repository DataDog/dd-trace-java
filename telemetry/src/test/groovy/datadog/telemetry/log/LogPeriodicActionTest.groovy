package datadog.telemetry.log


import datadog.telemetry.TelemetryService
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.api.telemetry.TelemetryLogEntry
import datadog.trace.logging.LogLevel
import datadog.trace.test.util.DDSpecification

class LogPeriodicActionTest extends DDSpecification {
  LogPeriodicAction periodicAction = new LogPeriodicAction()
  TelemetryService telemetryService = Mock()

  @Override
  void setup(){
    injectSysConfig("dd.instrumentation.telemetry.debug", "true")
    LogCollector.get().setEnabled(true)
    LogCollector.get().drain()
  }

  @Override
  void cleanup(){
    LogCollector.get().drain()
  }

  void 'push exception into the telemetry service'() {
    setup:
    LogCollector.get().addLogEntry("test", LogLevel.ERROR.toString(), new Exception("test"), null, null, null, null)

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogEntry( { TelemetryLogEntry logEntry ->
      logEntry.getMessage() == 'test'
    } )
    0 * _._
  }
}

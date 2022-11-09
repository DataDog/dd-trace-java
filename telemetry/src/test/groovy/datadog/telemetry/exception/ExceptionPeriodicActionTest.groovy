package datadog.telemetry.exception

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Log
import datadog.trace.api.ExceptionsCollector
import spock.lang.Specification

class ExceptionPeriodicActionTest extends Specification {
  ExceptionPeriodicAction periodicAction = new ExceptionPeriodicAction()
  TelemetryService telemetryService = Mock()

  void 'push exception into the telemetry service'() {
    setup:
    ExceptionsCollector.get().addException(new Exception("test"))

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addException( { Log exception ->
      exception.message == 'test' 
    } )
    0 * _._
  }
}

package datadog.telemetry.log

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.LogMessage
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger
import datadog.slf4j.impl.StaticLoggerBinder

class LogFilteringTest extends DDSpecification {
  Logger logger
  TelemetryService telemetryService = Mock()
  LogPeriodicAction periodicAction = new LogPeriodicAction()

  @Override
  void setup(){
    injectSysConfig("dd.instrumentation.telemetry.debug", "true")
    injectSysConfig("dd.telemetry.log-collection.enabled", "true")
    logger = StaticLoggerBinder.getSingleton().getLoggerFactory().getLogger("pepe")
    LogCollector.get().drain()
  }

  void 'stack trace filtering for non datadog exceptions'(){
    setup:
    logger.error("Debug message", new Exception("Exception message"))

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage( { LogMessage logMessage ->
      logMessage.getMessage() == 'Debug message'
      String[] stackTraceLines = logMessage.stackTrace.split("\r\n")

      stackTraceLines[0] == "java.lang.Exception"
      ExceptionHelper.isDataDogOrJava(stackTraceLines[1])
      ExceptionHelper.isDataDogOrJava(stackTraceLines[2])
      ExceptionHelper.isDataDogOrJava(stackTraceLines[3])
      ExceptionHelper.isDataDogOrJava(stackTraceLines[4])
    } )
    0 * _._
  }

  void 'stack trace filtering for datadog exceptions'(){
    setup:
    try {
      ExceptionHelper.throwExceptionFromDatadogCode("Exception Message")
    }
    catch (Exception e){
      logger.error("Debug message", e)
    }

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage( { LogMessage logMessage ->
      logMessage.getMessage() == 'Debug message'
      String[] stackTraceLines = logMessage.stackTrace.split("\r\n")

      stackTraceLines[0] == "java.lang.Exception: Exception Message"
      ExceptionHelper.isDataDogOrJava(stackTraceLines[1])
      ExceptionHelper.isDataDogOrJava(stackTraceLines[2])
      ExceptionHelper.isDataDogOrJava(stackTraceLines[3])
      ExceptionHelper.isDataDogOrJava(stackTraceLines[4])
    } )
    0 * _._
  }
}

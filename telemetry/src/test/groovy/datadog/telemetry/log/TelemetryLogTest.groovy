package datadog.telemetry.log

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.LogMessage
import datadog.trace.api.LogCollector
import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger
import datadog.slf4j.impl.StaticLoggerBinder

class TelemetryLogTest extends DDSpecification {
  LogPeriodicAction periodicAction = new LogPeriodicAction()
  TelemetryService telemetryService = Mock()
  Logger logger

  @Override
  void setup(){
    injectSysConfig("dd.instrumentation.telemetry.debug", "true")
    logger = StaticLoggerBinder.getSingleton().getLoggerFactory().getLogger("pepe")
    LogCollector.get().setEnabled(true)
    LogCollector.get().drain()
  }

  @Override
  void cleanup(){
    LogCollector.get().drain()
  }

  void 'test static binder'(){
    when:
    logger.error("Exception Message", new Exception("Exception Message"))
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage(_)
    0 * _._
  }


  void 'test marker debugging'(){
    when:

    logger.warn(LogCollector.SEND_TELEMETRY, "Debug Message")
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogMessage( { LogMessage exception ->
      exception.message == 'Debug Message'
    } )
    0 * _._
  }

  void 'test debugging with no marker'(){
    when:
    logger.warn("Debug Message")
    periodicAction.doIteration(telemetryService)

    then:
    0 * _._
  }

  void 'test overflowed entries'(){
    setup:
    List<LogCollector.RawLogMessage> entries

    when:
    for (int i=0; i< LogCollector.DEFAULT_MAX_CAPACITY + 10; i++){
      logger.warn(LogCollector.SEND_TELEMETRY, "Debug Message " + i)
    }
    entries = LogCollector.get().drain()

    then:
    entries.size() == LogCollector.DEFAULT_MAX_CAPACITY
    0 * _._
  }

  void 'test rate limiting of repeated log messages'(){
    setup:
    List<LogCollector.RawLogMessage> entries
    def messageToSend = 10

    when:
    for (int i=0; i<messageToSend; i++){
      logger.warn(LogCollector.SEND_TELEMETRY, "Debug Message 1")
    }
    for (int i=0; i<messageToSend; i++){
      logger.warn(LogCollector.SEND_TELEMETRY, "Debug Message 2")
    }
    entries = LogCollector.get().drain()

    then:
    entries.size() == 2
    entries.get(0).message == "Debug Message 2, {${messageToSend}} additional messages skipped"
    entries.get(1).message == "Debug Message 1, {${messageToSend}} additional messages skipped"
    0 * _._
  }
}
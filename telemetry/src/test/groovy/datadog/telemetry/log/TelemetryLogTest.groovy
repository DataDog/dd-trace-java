package datadog.telemetry.log

import datadog.slf4j.impl.StaticLoggerBinder
import datadog.telemetry.TelemetryService
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.api.telemetry.TelemetryLogEntry
import datadog.trace.logging.ddlogger.DDLogger
import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger

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
    1 * telemetryService.addLogEntry( { TelemetryLogEntry logEntry ->
      logEntry.message == 'Exception Message'
    } )
    0 * _._
  }


  void 'test marker debugging'(){
    when:

    logger.warn(DDLogger.SEND_TELEMETRY, "Debug Message")
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addLogEntry( { TelemetryLogEntry exception ->
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
    List<TelemetryLogEntry> entries

    when:
    for (int i=0; i< LogCollector.MAX_ENTRIES + 10; i++){
      logger.warn(DDLogger.SEND_TELEMETRY, "Debug Message " + i)
    }
    entries = LogCollector.get().drain()

    then:
    entries.size() == LogCollector.MAX_ENTRIES + 1
    entries.get(LogCollector.MAX_ENTRIES).getMessage().contains("Omitted")
    0 * _._
  }

  void 'test rate limiting of repeated log messages'(){
    setup:
    List<TelemetryLogEntry> entries

    when:
    for (int i=0; i< LogCollector.MAX_ENTRIES + 10; i++){
      logger.warn(DDLogger.SEND_TELEMETRY, "Debug Message 1")
    }
    for (int i=0; i< LogCollector.MAX_ENTRIES + 10; i++){
      logger.warn(DDLogger.SEND_TELEMETRY, "Debug Message 2")
    }
    entries = LogCollector.get().drain()

    then:
    entries.size() == 2
    entries.get(0).getMessage().equals("Debug Message 1")
    entries.get(1).getMessage().equals("Debug Message 2")
    0 * _._
  }
}

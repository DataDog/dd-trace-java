package datadog.telemetry.log

import datadog.slf4j.impl.StaticLoggerBinder
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.logging.ddlogger.DDLogger
import datadog.trace.test.util.DDSpecification

class TelemetryDebugEnabledFlagTest  extends DDSpecification {

  @Override
  void setup(){
    LogCollector.get().setEnabled(true)
    LogCollector.get().drain()
  }
  def 'test log collection debug flag enabled'(){
    setup:
    LogCollector.get().setDebugEnabled(true)
    def logger = StaticLoggerBinder.getSingleton().getLoggerFactory().getLogger("pepe")

    when:
    logger.debug(DDLogger.SEND_TELEMETRY, "debug message")
    logger.debug(DDLogger.SEND_TELEMETRY, "debug exception", ExceptionHelper.createException("Exception message"))
    def list = LogCollector.get().drain()

    then:
    list.get(0).getMessage() == "debug message"
    list.get(1).getMessage() == "debug exception"
  }

  def 'test log collection debug flag disabled'(){
    setup:
    LogCollector.get().setDebugEnabled(false)
    def logger = StaticLoggerBinder.getSingleton().getLoggerFactory().getLogger("pepe")

    when:
    logger.debug(DDLogger.SEND_TELEMETRY, "debug message")
    logger.debug(DDLogger.SEND_TELEMETRY, "debug exception", ExceptionHelper.createException("Exception message"))
    def list = LogCollector.get().drain()

    then:
    list.size() == 0
  }
}

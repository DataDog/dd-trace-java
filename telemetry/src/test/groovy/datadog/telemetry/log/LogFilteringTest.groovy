package datadog.telemetry.log

import datadog.slf4j.impl.StaticLoggerBinder
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger

class LogFilteringTest extends DDSpecification{
  Logger logger

  @Override
  void setup(){
    injectSysConfig("dd.instrumentation.telemetry.debug", "true")
    logger = StaticLoggerBinder.getSingleton().getLoggerFactory().getLogger("pepe")
    LogCollector.get().setEnabled(true)
    LogCollector.get().drain()
  }

  void 'stack trace filtering for non datadog exceptions'(){
    setup:

    when:
    logger.error("Debug message", new Exception("Exception message"))
    def stackTrace = LogCollector.get().drain().get(0).getStackTrace()
    String[] stackTraceLines = stackTrace.split("\r\n")

    then:
    stackTraceLines[0] == "java.lang.Exception"
    ExceptionHelper.isDataDogOrJava(stackTraceLines[1])
    ExceptionHelper.isDataDogOrJava(stackTraceLines[2])
    ExceptionHelper.isDataDogOrJava(stackTraceLines[3])
    ExceptionHelper.isDataDogOrJava(stackTraceLines[4])
  }

  void 'stack trace filtering for datadog exceptions'(){
    when:
    try {
      ExceptionHelper.throwExceptionFromDatadogCode("Exception Message")
    }
    catch (Exception e){
      logger.error("Debug message", e)
    }
    def stackTrace = LogCollector.get().drain().get(0).getStackTrace()
    String[] stackTraceLines = stackTrace.split("\r\n")

    then:
    stackTraceLines[0] == "java.lang.Exception: Exception Message"
    ExceptionHelper.isDataDogOrJava(stackTraceLines[1])
    ExceptionHelper.isDataDogOrJava(stackTraceLines[2])
    ExceptionHelper.isDataDogOrJava(stackTraceLines[3])
    ExceptionHelper.isDataDogOrJava(stackTraceLines[4])
    ExceptionHelper.isDataDogOrJava(stackTraceLines[4])
  }
}

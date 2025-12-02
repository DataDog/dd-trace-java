package datadog.trace.instrumentation.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.logging.intake.LogsIntake;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class LogsIntakeHelper {

  public static void log(ILoggingEvent event) {
    LogsIntake.log(map(event));
  }

  private static Map<String, Object> map(ILoggingEvent event) {
    Map<String, Object> log = new HashMap<>();
    log.put("thread", event.getThreadName());
    log.put("level", event.getLevel().levelStr);
    log.put("loggerName", event.getLoggerName());
    log.put("message", event.getFormattedMessage());
    if (event.getThrowableProxy() != null) {
      Map<String, Object> thrownLog = new HashMap<>();
      thrownLog.put("message", event.getThrowableProxy().getMessage());
      thrownLog.put("name", event.getThrowableProxy().getClassName());
      String stackTraceString =
          Arrays.stream(event.getThrowableProxy().getStackTraceElementProxyArray())
              .map(StackTraceElementProxy::getSTEAsString)
              .collect(Collectors.joining(" "));
      thrownLog.put("extendedStackTrace", stackTraceString);
      log.put("thrown", thrownLog);
    }
    String traceId = CorrelationIdentifier.getTraceId();
    if (traceId != null && !traceId.equals("0")) {
      log.put("dd.trace_id", traceId);
    }
    String spanId = CorrelationIdentifier.getSpanId();
    if (spanId != null && !spanId.equals("0")) {
      log.put("dd.span_id", spanId);
    }
    return log;
  }
}

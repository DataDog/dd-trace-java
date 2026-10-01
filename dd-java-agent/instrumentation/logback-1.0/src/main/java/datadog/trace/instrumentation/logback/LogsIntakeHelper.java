package datadog.trace.instrumentation.logback;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.logging.intake.LogsIntake;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class LogsIntakeHelper {

  private static final boolean APP_LOGS_COLLECTION = Config.get().isAppLogsCollectionEnabled();
  private static final Level SUBMISSION_LEVEL =
      Level.toLevel(Config.get().getAgentlessLogSubmissionLevel(), Level.INFO);

  public static void log(ILoggingEvent event) {
    // App-log collection keeps its existing payload and framework-level filtering, even when
    // both collection flags are enabled.
    if (APP_LOGS_COLLECTION || event.getLevel().isGreaterOrEqual(SUBMISSION_LEVEL)) {
      LogsIntake.log(map(event));
    }
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
    if (!APP_LOGS_COLLECTION) {
      Map<String, Object> context = new HashMap<>();
      // Capture on the producer thread, independently of the ordering of the two entry advices.
      // Injection-disabled logs retain user MDC but do not gain automatic correlation IDs.
      if (traceConfig().isLogsInjectionEnabled()) {
        addCorrelationIds(context);
      }
      Map<String, String> mdc = event.getMDCPropertyMap();
      if (mdc != null) {
        context.putAll(mdc);
      }
      log.put("contextMap", context);
      log.put("timestamp", event.getTimeStamp());
    } else {
      addCorrelationIds(log);
    }
    return log;
  }

  private static void addCorrelationIds(Map<String, Object> log) {
    String traceId = CorrelationIdentifier.getTraceId();
    if (traceId != null && !traceId.equals("0")) {
      log.put("dd.trace_id", traceId);
    }
    String spanId = CorrelationIdentifier.getSpanId();
    if (spanId != null && !spanId.equals("0")) {
      log.put("dd.span_id", spanId);
    }
  }
}

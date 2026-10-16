package datadog.trace.bootstrap.instrumentation.log;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.logging.intake.LogsIntake;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Submits events captured by logging backend instrumentations to the agentless logs intake.
 *
 * <p>Events use the payload shape of Log4j's JSON layout, which the logs backend already parses.
 * Callers must capture events on the producer thread, so that the correlation identifiers match the
 * active span.
 */
public final class AgentlessLogSubmission {

  public static final String TRACE = "TRACE";
  public static final String DEBUG = "DEBUG";
  public static final String INFO = "INFO";
  public static final String WARN = "WARN";
  public static final String ERROR = "ERROR";
  public static final String FATAL = "FATAL";

  private static final int MAX_STACKTRACE_STRING_LENGTH = 16 * 1_024;

  private static final int SUBMISSION_SEVERITY =
      severity(Config.get().getAgentlessLogSubmissionLevel());

  private AgentlessLogSubmission() {}

  /** Returns whether events of the given level reach the configured submission level. */
  public static boolean isLevelEnabled(String level) {
    return severity(level) >= SUBMISSION_SEVERITY;
  }

  /** Converts a {@link java.util.logging.Level} integer value to a level name of this class. */
  public static String julLevel(int value) {
    if (value >= 1100) {
      return FATAL;
    } else if (value >= 1000) {
      return ERROR;
    } else if (value >= 900) {
      return WARN;
    } else if (value >= 800) {
      return INFO;
    } else if (value >= 500) {
      return DEBUG;
    }
    return TRACE;
  }

  /**
   * Submits an event.
   *
   * @param level a level name of this class
   * @param context the user context of the event, such as the MDC, or {@code null}
   */
  public static void submit(
      String thread,
      String level,
      String loggerName,
      String message,
      Throwable thrown,
      long timestamp,
      Map<String, String> context) {
    Map<String, Object> log = new HashMap<>();
    log.put("thread", thread);
    log.put("level", level);
    log.put("loggerName", loggerName);
    log.put("message", message);
    log.put("timestamp", timestamp);
    if (thrown != null) {
      log.put("thrown", thrown(thrown));
    }
    Map<String, String> contextMap = new HashMap<>();
    if (traceConfig().isLogsInjectionEnabled()) {
      addCorrelationValues(contextMap);
    }
    if (context != null) {
      contextMap.putAll(context);
    }
    log.put("contextMap", contextMap);
    LogsIntake.log(log);
  }

  private static Map<String, Object> thrown(Throwable thrown) {
    Map<String, Object> thrownLog = new HashMap<>();
    thrownLog.put("message", thrown.getMessage());
    thrownLog.put("name", thrown.getClass().getCanonicalName());
    StringWriter stringWriter = new StringWriter();
    thrown.printStackTrace(new PrintWriter(stringWriter));
    StringBuffer stackTrace = stringWriter.getBuffer();
    thrownLog.put(
        "extendedStackTrace",
        stackTrace.substring(0, Math.min(stackTrace.length(), MAX_STACKTRACE_STRING_LENGTH)));
    return thrownLog;
  }

  private static void addCorrelationValues(Map<String, String> context) {
    Config config = Config.get();
    putIfNotEmpty(context, Tags.DD_SERVICE, config.getServiceName());
    putIfNotEmpty(context, Tags.DD_ENV, config.getEnv());
    putIfNotEmpty(context, Tags.DD_VERSION, config.getVersion());
    String traceId = CorrelationIdentifier.getTraceId();
    if (traceId != null && !traceId.equals("0")) {
      context.put(CorrelationIdentifier.getTraceIdKey(), traceId);
    }
    String spanId = CorrelationIdentifier.getSpanId();
    if (spanId != null && !spanId.equals("0")) {
      context.put(CorrelationIdentifier.getSpanIdKey(), spanId);
    }
  }

  private static void putIfNotEmpty(Map<String, String> context, String key, String value) {
    if (value != null && !value.isEmpty()) {
      context.put(key, value);
    }
  }

  static int severity(String level) {
    switch (level == null ? INFO : level.toUpperCase(Locale.ROOT)) {
      case "ALL":
        return 0;
      case TRACE:
        return 1;
      case DEBUG:
        return 2;
      case WARN:
        return 4;
      case ERROR:
        return 5;
      case FATAL:
        return 6;
      case "OFF":
        return 7;
      default:
        return 3;
    }
  }
}

package datadog.trace.instrumentation.log4j1;

import datadog.trace.bootstrap.instrumentation.log.AgentlessLogSubmission;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import org.apache.log4j.MDC;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;

public final class LogsIntakeHelper {

  private LogsIntakeHelper() {}

  public static void submit(LoggingEvent event) {
    String level = level(event.level.toInt());
    if (!AgentlessLogSubmission.isLevelEnabled(level)) {
      return;
    }
    ThrowableInformation throwable = event.getThrowableInformation();
    AgentlessLogSubmission.submit(
        event.getThreadName(),
        level,
        event.categoryName,
        event.getRenderedMessage(),
        throwable != null ? throwable.getThrowable() : null,
        event.timeStamp,
        context());
  }

  /** Converts a Log4j 1 level to a level name of {@link AgentlessLogSubmission}. */
  static String level(int level) {
    if (level >= 50_000) {
      return AgentlessLogSubmission.FATAL;
    } else if (level >= 40_000) {
      return AgentlessLogSubmission.ERROR;
    } else if (level >= 30_000) {
      return AgentlessLogSubmission.WARN;
    } else if (level >= 20_000) {
      return AgentlessLogSubmission.INFO;
    } else if (level >= 10_000) {
      return AgentlessLogSubmission.DEBUG;
    }
    return AgentlessLogSubmission.TRACE;
  }

  private static Map<String, String> context() {
    Hashtable<?, ?> mdc = MDC.getContext();
    if (mdc == null) {
      return null;
    }
    Map<String, String> context = new HashMap<>();
    for (Map.Entry<?, ?> entry : mdc.entrySet()) {
      context.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return context;
  }
}

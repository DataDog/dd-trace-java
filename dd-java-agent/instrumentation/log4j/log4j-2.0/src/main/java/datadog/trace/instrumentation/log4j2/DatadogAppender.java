package datadog.trace.instrumentation.log4j2;

import datadog.trace.api.logging.intake.LogsIntake;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;

public class DatadogAppender extends AbstractAppender {

  private static final int MAX_STACKTRACE_STRING_LENGTH = 16 * 1_024;

  public DatadogAppender(String name, Filter filter) {
    super(name, filter, null);
  }

  @Override
  public void append(final LogEvent event) {
    LogsIntake.log(map(event));
  }

  /**
   * This method follows the structure and naming of Log4j's JSON layout. The idea is that logs
   * backend already knows how to parse logs coming from Log4j so the same format is used here. The
   * reason for re-implementing this rather than using the existing layout class is that the latter
   * requires additional dependencies that may or may not be present in the traced application.
   */
  private Map<String, Object> map(final LogEvent event) {
    Map<String, Object> log = new HashMap<>();
    log.put("thread", event.getThreadName());
    log.put("level", event.getLevel().name());
    log.put("loggerName", event.getLoggerName());
    log.put("message", event.getMessage().getFormattedMessage());

    Throwable thrown = event.getThrown();
    if (thrown != null) {
      Map<String, Object> thrownLog = new HashMap<>();
      thrownLog.put("message", thrown.getMessage());
      thrownLog.put("name", thrown.getClass().getCanonicalName());

      // TODO consider using structured stack trace layout
      //  (see
      // org.apache.logging.log4j.layout.template.json.resolver.ExceptionResolver#createStackTraceResolver)
      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      thrown.printStackTrace(printWriter);
      StringBuffer stackTraceBuffer = stringWriter.getBuffer();
      String stackTraceString =
          stackTraceBuffer.substring(
              0, Math.min(stackTraceBuffer.length(), MAX_STACKTRACE_STRING_LENGTH));
      thrownLog.put("extendedStackTrace", stackTraceString);

      log.put("thrown", thrownLog);
    }

    log.put("contextMap", event.getContextMap());
    log.put("endOfBatch", event.isEndOfBatch());
    log.put("loggerFqcn", event.getLoggerFqcn());

    StackTraceElement source = event.getSource();
    Map<String, Object> sourceLog = new HashMap<>();
    sourceLog.put("class", source.getClassName());
    sourceLog.put("method", source.getMethodName());
    sourceLog.put("file", source.getFileName());
    sourceLog.put("line", source.getLineNumber());
    log.put("source", sourceLog);

    return log;
  }
}

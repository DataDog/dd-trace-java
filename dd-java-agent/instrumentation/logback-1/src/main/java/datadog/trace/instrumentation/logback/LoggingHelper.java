package datadog.trace.instrumentation.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanEvent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

public class LoggingHelper {
  public static void createSpanEvent(ILoggingEvent event, AgentSpan span) {
    SpanAttributes.Builder builder = SpanAttributes.builder()
        .put("logger", event.getLoggerName())
        .put("level", event.getLevel().toString())
        .put("thread", event.getThreadName())
        .put("message", event.getFormattedMessage());

    if (Level.ERROR.equals(event.getLevel())) {
      span.setError(true);
    }

    Throwable throwable = null;
    IThrowableProxy throwableProxy = event.getThrowableProxy();
    if (throwableProxy instanceof ThrowableProxy) {
      // the returned Throwable might still be null
      throwable = ((ThrowableProxy)throwableProxy).getThrowable();
    }
    if (throwable != null) {
      final StringWriter errorString = new StringWriter();
      throwable.printStackTrace(new PrintWriter(errorString));

      builder.put("exception.message", throwable.getMessage());
      builder.put("exception.type", throwable.getClass().getName());
      builder.put("exception.stacktrace", errorString.toString());

      span.setTag("error.message", throwable.getMessage());
      span.setTag("error.type", throwable.getClass().getName());
      span.setTag("error.stack", errorString.toString());
    }

    span.addEvent(new SpanEvent("LogEvent", builder.build(), event.getTimeStamp(), TimeUnit.MILLISECONDS));
  }
}

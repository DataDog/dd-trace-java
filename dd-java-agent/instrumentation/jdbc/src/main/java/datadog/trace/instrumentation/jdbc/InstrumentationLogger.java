package datadog.trace.instrumentation.jdbc;

import org.slf4j.LoggerFactory;

public class InstrumentationLogger {
  public static void debug(
      String instrumentation, final Class<?> target, final Throwable throwable) {
    // log exception
    LoggerFactory.getLogger(instrumentation)
        .debug("Failed to handle exception in instrumentation for " + target, throwable);
  }
}

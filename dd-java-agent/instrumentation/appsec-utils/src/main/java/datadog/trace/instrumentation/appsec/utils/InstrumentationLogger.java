package datadog.trace.instrumentation.appsec.utils;

import org.slf4j.LoggerFactory;

public class InstrumentationLogger {
  public static void debug(
      String instrumentation, final Class<?> target, final Throwable throwable) {
    LoggerFactory.getLogger(instrumentation)
        .debug("Failed to handle exception in instrumentation for " + target, throwable);
  }
}

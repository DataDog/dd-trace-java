package datadog.trace.instrumentation.log4j27;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DebugHelper extends Exception {

  private static final Logger log = LoggerFactory.getLogger(DebugHelper.class);

  public static void debugLog(String message, Throwable throwable) {
    if (log.isDebugEnabled()) {
      log.debug(message, throwable);
    }
  }

  public static void debugLog(String message, Object o1, Object o2) {
    if (log.isDebugEnabled()) {
      log.debug(message, o1, o2);
    }
  }

  public static void debugLog(String message, Object o1, Object o2, Object o3) {
    if (log.isDebugEnabled()) {
      log.debug(message, o1, o2, o3);
    }
  }

  public static void debugLogWithException(String message, Object o1) {
    if (log.isDebugEnabled()) {
      DebugHelper exception = new DebugHelper();
      log.debug(message, o1, exception);
    }
  }
}

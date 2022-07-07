package com.datadog.debugger.util;

import datadog.trace.relocate.api.RatelimitedLogger;
import java.util.Arrays;
import org.slf4j.Logger;

/** Helper class for rate limiting & logging exceptions */
public class ExceptionHelper {
  private static final Object[] EMPTY_ARRAY = new Object[0];

  public static void logException(Logger log, Throwable ex, String message, Object... params) {
    if (log.isDebugEnabled()) {
      if (params == null) {
        params = EMPTY_ARRAY;
      }
      Object[] fullParams = Arrays.copyOf(params, params.length + 1);
      fullParams[params.length] = ex;
      log.debug(message, fullParams);
    } else {
      log.warn(message + " " + ex.toString(), params);
    }
  }

  public static void rateLimitedLogException(
      RatelimitedLogger ratelimitedLogger,
      Logger log,
      Throwable ex,
      String message,
      Object... params) {
    if (log.isDebugEnabled()) {
      logException(log, ex, message, params);
    } else {
      ratelimitedLogger.warn(message + " " + ex.toString(), params);
    }
  }
}

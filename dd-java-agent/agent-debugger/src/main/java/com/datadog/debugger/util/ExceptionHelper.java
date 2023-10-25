package com.datadog.debugger.util;

import datadog.trace.relocate.api.RatelimitedLogger;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
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

  public static String foldExceptionStackTrace(Throwable t) {
    StringWriter writer = new StringWriter();
    t.printStackTrace(new NoNewLinePrintWriter(writer));
    return writer.toString();
  }

  private static class NoNewLinePrintWriter extends PrintWriter {
    public NoNewLinePrintWriter(Writer out) {
      super(out);
    }

    @Override
    public void println() {}

    @Override
    public void write(String s, int off, int len) {
      super.write(s.replace('\t', ' '), off, len);
    }
  }
}

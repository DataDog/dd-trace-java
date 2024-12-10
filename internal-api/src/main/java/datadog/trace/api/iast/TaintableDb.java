package datadog.trace.api.iast;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface TaintableDb {

  int $$DD$getRecordsRead();

  void $$DD$setRecordsRead(final int recordsRead);

  @SuppressForbidden
  class DebugLogger {
    private static final Logger LOGGER;

    static {
      try {
        LOGGER = LoggerFactory.getLogger("TaintableDb tainted objects");
        Class<?> levelCls = Class.forName("ch.qos.logback.classic.Level");
        Method setLevel = LOGGER.getClass().getMethod("setLevel", levelCls);
        Object debugLevel = levelCls.getField("DEBUG").get(null);
        setLevel.invoke(LOGGER, debugLevel);
      } catch (IllegalAccessException
          | NoSuchFieldException
          | ClassNotFoundException
          | NoSuchMethodException
          | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }

    public static void logTaint(TaintableDb t) {
      String content;
      if (t.getClass().getName().startsWith("java.")) {
        content = t.toString();
      } else {
        content = "(value not shown)"; // toString() may trigger tainting
      }
      LOGGER.debug(
          "taint: {}[{}] {}",
          t.getClass().getSimpleName(),
          Integer.toHexString(System.identityHashCode(t)),
          content);
    }
  }
}

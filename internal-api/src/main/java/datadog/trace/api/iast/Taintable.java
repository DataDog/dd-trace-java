package datadog.trace.api.iast;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface Taintable {

  Source $$DD$getSource();

  void $$DD$setSource(final Source source);

  default boolean $DD$isTainted() {
    return $$DD$getSource() != null;
  }

  /** Interface to isolate customer classloader from our classes */
  interface Source {
    byte getOrigin();

    String getName();

    String getValue();
  }

  @SuppressForbidden
  class DebugLogger {
    private static final Logger LOGGER;

    static {
      LOGGER = LoggerFactory.getLogger("Taintable tainted objects");

      // Check logger class by name to avoid NoClassDefFoundError at runtime for tests without
      // Logback.
      if (LOGGER.getClass().getName().equals("ch.qos.logback.classic.Logger")) {
        try {
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
    }

    public static void logTaint(Taintable t) {
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

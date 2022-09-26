package datadog.trace.common.reporting;

import java.util.HashMap;
import java.util.Map;

/**
 * LoggerFactory used to produce and get Logger objects for Java classes.
 */
public class LoggerFactory {

  // TODO: need to figure out how to garbage collect Loggers after they are out of use in the mapping
  private static Map<String, Logger> classNameToLogger = new HashMap<>();

  /**
   * Returns Logger (our implementation) depending on specific className. Threadsafe (needs testing) through
   * synchronization to prevent any sort of race condition for insertions and checks.
   * @param className
   * @return
   */
  public static Logger getLogger(String className) {
    Logger currentLogger = classNameToLogger.get(className);
    if (currentLogger == null) {
      // TODO: replace synchronization w/ computeIfAbsent after Java 1.8 migration
      synchronized (currentLogger) {
        currentLogger = classNameToLogger.get(className);
        if (currentLogger == null) {
          currentLogger = new Logger(className);
          classNameToLogger.put(className, currentLogger);
        }
      }
    }
    return currentLogger;
  }

  /**
   * Returns a logger for inputs of type class
   * @param klass
   * @return
   */
  public static Logger getLogger(Class klass) {
    return getLogger(klass.getName());
  }
}

package datadog.trace.common.reporting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logger class which will be created for each class which encounters an exception. Key feature is error
 * reporting back to Datadog. LinkedHashMap used effectively as a LRU cache for seen exceptions to make sure
 * that if exceptions are seen beforehand, there is no excessive log spew due to printing of stack traces.
 *
 * Also makes use of slf4j Logger to effectively extend logging capabilities through additional
 * internal reporting functions which can be added or retracted to the class depending on developer needs.
 *
 * Named Logger to make for easy importing and implementation into existing classes which already make use of
 * slf4j logging without excessively changing any existing code.
 */
public class Logger {
  private final static int MAX_SEEN_EXCEPTION_CAPACITY = 10;
  private final static String DUMMY_VALUE = ""; // used for seenExceptions insertions

  // Want linked hash map data structure without the mapping function (effectively using as LRU hash set)
  // Thus, use of dummy value as the insertion value
  private LinkedHashMap<StackTraceWrapper, String> seenExceptions = new LinkedHashMap(MAX_SEEN_EXCEPTION_CAPACITY,
      0.75F, true) {
    protected boolean removeEldestEntry(Map.Entry eldest) {
      return true;
    }
  };

  String className;
  org.slf4j.Logger LOGGER;

  /**
   * Constructor for Logger
   * @param className
   */
  public Logger(String className) {
    this.className = className;
    this.LOGGER = org.slf4j.LoggerFactory.getLogger(className);
  }

  /**
   * Error function most currently iterating loggers are being called upon. Conditionally logs a stack trace
   * or not depending on if a exception was seen before (seen in the seenExceptions LRU cache).
   * @param msg
   * @param t
   */
  public void error(String msg, Throwable t) {
    StackTraceWrapper currentError = new StackTraceWrapper(msg, t.getStackTrace());

    // Using DUMMY_VALUE because we don't actually care about what's used as the "value" for currentError key
    // Because we set accessOrder: true on seenExceptions, re-adding same key updates position in LinkedHashMap
    boolean isFirstTimeSeen = (seenExceptions.put(currentError, DUMMY_VALUE) == null);
    if (isFirstTimeSeen) {
      errorWithStackTrace(msg, t);
    }
    else {
      errorNoStackTrace(msg, t);
    }
  }

  /**
   * Reports exception to Datadog backend and prints erorr message as well as stack trace in logs.
   * @param msg
   * @param e
   */
  private void errorWithStackTrace(String msg, Throwable e) {
    reportException(msg, e);
    LOGGER.debug(msg, e);
  }

  /**
   * Reporst exception to Datadog backend and just prints error message.
   * @param msg
   * @param e
   */
  private void errorNoStackTrace(String msg, Throwable e) {
    reportException(msg, e);
    LOGGER.debug(msg);
  }

  /**
   * Reports exception to Datadog backend.
   * @param msg
   * @param e
   */
  public void reportException(String msg, Throwable e) {
    // Update the exception cache

    // Send report/stack track to Datadog using Telemetry or something
  }
}

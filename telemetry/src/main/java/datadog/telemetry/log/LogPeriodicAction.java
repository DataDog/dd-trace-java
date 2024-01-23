package datadog.telemetry.log;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.LogMessageLevel;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.util.stacktrace.StackUtils;

public class LogPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  /**
   * The current list of packages passed in is small, but if it kept growing and this did become a
   * performance issue then we could consider using ClassNameTrie instead (ie. use the builder to
   * create the trie and store it as a constant in LogPeriodicAction to be passed in here and used
   * as a filter)
   */
  static final String[] PACKAGE_LIST = {"datadog.", "com.datadog.", "java.", "javax.", "jakarta."};

  private static final String RET = "\r\n";
  private static final String UNKNOWN = "<unknown>";

  @Override
  public void doIteration(TelemetryService service) {
    for (LogCollector.RawLogMessage rawLogMsg : LogCollector.get().drain()) {

      LogMessage logMessage =
          new LogMessage()
              .message(rawLogMsg.message)
              .tracerTime(rawLogMsg.timestamp)
              .count(rawLogMsg.count);

      if (rawLogMsg.logLevel != null) {
        logMessage.level(LogMessageLevel.fromString(rawLogMsg.logLevel));
      }

      if (rawLogMsg.throwable != null) {
        logMessage.stackTrace(renderStackTrace(rawLogMsg.throwable));
      }

      service.addLogMessage(logMessage);
    }
  }

  private static String renderStackTrace(Throwable t) {
    StringBuilder stackTrace = new StringBuilder();

    String name = t.getClass().getCanonicalName();
    if (name == null || name.isEmpty()) {
      stackTrace.append(UNKNOWN);
    } else {
      stackTrace.append(name);
    }

    if (isDataDogCode(t)) {
      String msg = t.getMessage();
      stackTrace.append(": ");
      if (msg == null || msg.isEmpty()) {
        stackTrace.append(UNKNOWN);
      } else {
        stackTrace.append(msg);
      }
    }
    stackTrace.append(RET);

    Throwable filtered = StackUtils.filterPackagesIn(t, PACKAGE_LIST);
    for (StackTraceElement stackTraceElement : filtered.getStackTrace()) {
      stackTrace.append("  at ").append(stackTraceElement).append(RET);
    }
    return stackTrace.toString();
  }

  private static boolean isDataDogCode(Throwable t) {
    StackTraceElement[] stackTrace = t.getStackTrace();
    if (stackTrace == null || stackTrace.length == 0) {
      return false;
    }
    String cn = stackTrace[0].getClassName();
    if (cn.isEmpty()) {
      return false;
    }
    return cn.startsWith("datadog.") || cn.startsWith("com.datadog.");
  }
}

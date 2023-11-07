package datadog.telemetry.log;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.LogMessageLevel;
import datadog.trace.api.LogCollector;
import datadog.trace.util.stacktrace.StackUtils;

public class LogPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  static final String[] PACKAGE_LIST = {"datadog.", "com.datadog.", "java.", "javax."};
  private static final String RET = "\r\n";
  private static final String UNKNOWN = "<unknown>";

  @Override
  public void doIteration(TelemetryService service) {
    for (LogCollector.RawLogMessage rawLogMsg : LogCollector.get().drain()) {

      LogMessage logMessage =
          new LogMessage().message(rawLogMsg.message).tracerTime(rawLogMsg.timestamp);

      if (rawLogMsg.logLevel != null) {
        logMessage.level(LogMessageLevel.fromValue(rawLogMsg.logLevel));
      }

      if (rawLogMsg.throwable != null) {
        logMessage.stackTrace(renderStackTrace(rawLogMsg.throwable));
      }

      service.addLogMessage(logMessage);
    }
  }

  private String renderStackTrace(Throwable t) {
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
      stackTrace.append("  at ");
      stackTrace.append(stackTraceElement.toString());
      stackTrace.append(RET);
    }
    return stackTrace.toString();
  }

  private boolean isDataDogCode(Throwable t) {
    StackTraceElement[] stackTrace = t.getStackTrace();
    if (stackTrace.length == 0) {
      return false;
    }
    String cn = stackTrace[0].getClassName();
    if (cn.isEmpty()) {
      return false;
    }
    return cn.startsWith("datadog.") || cn.startsWith("com.datadog.");
  }
}

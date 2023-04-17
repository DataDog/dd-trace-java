package datadog.telemetry.log;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.LogMessageLevel;
import datadog.trace.api.LogCollector;
import datadog.trace.util.stacktrace.StackUtils;

public class LogPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  private static final String[] packageList = {"datadog.", "com.datadog.", "java.", "javax."};
  private static final String RET = "\r\n";

  @Override
  public void doIteration(TelemetryService service) {
    for (LogCollector.RawLogMessage rawLogMsg : LogCollector.get().drain()) {

      LogMessage logMessage =
          new LogMessage().message(rawLogMsg.message).tracerTime(rawLogMsg.timestamp);

      if (rawLogMsg.logLevel != null) {
        logMessage.setLevel(LogMessageLevel.fromValue(rawLogMsg.logLevel));
      }

      if (rawLogMsg.throwable != null) {
        logMessage.setStackTrace(renderStackTrace(rawLogMsg.throwable));
      }

      service.addLogMessage(logMessage);
    }
  }

  private String renderStackTrace(Throwable t) {
    StringBuilder stackTrace = new StringBuilder();
    stackTrace.append(t.getClass().getCanonicalName());

    String firstStackElementClassName = t.getStackTrace()[0].getClassName();
    if (isDataDogCode(t)) {
      stackTrace.append(": ");
      stackTrace.append(t.getMessage());
    }
    stackTrace.append(RET);

    Throwable filtered = StackUtils.filterPackagesIn(t, packageList);
    for (StackTraceElement stackTraceElement : filtered.getStackTrace()) {
      stackTrace.append("  at ");
      stackTrace.append(stackTraceElement.toString());
      stackTrace.append(RET);
    }
    return stackTrace.toString();
  }

  private boolean isDataDogCode(Throwable t) {
    String firstStackElementClassName = t.getStackTrace()[0].getClassName();
    return firstStackElementClassName.startsWith("datadog.")
        || firstStackElementClassName.startsWith("com.datadog.");
  }
}

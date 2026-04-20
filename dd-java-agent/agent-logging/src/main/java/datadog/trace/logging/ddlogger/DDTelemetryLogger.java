package datadog.trace.logging.ddlogger;

import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LoggerHelper;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

public class DDTelemetryLogger extends DDLogger {

  public DDTelemetryLogger(LoggerHelper helper, String name) {
    super(helper, name);
  }

  @Override
  public void formatLog(LogLevel level, Marker marker, String format, Object arg) {
    telemetryLog(level, marker, format, getIfThrowable(arg));
    super.formatLog(level, marker, format, arg);
  }

  @Override
  public void formatLog(LogLevel level, Marker marker, String format, Object arg1, Object arg2) {
    telemetryLog(level, marker, format, getIfThrowable(arg2));
    super.formatLog(level, marker, format, arg1, arg2);
  }

  @Override
  public void formatLog(LogLevel level, Marker marker, String format, Object... arguments) {
    telemetryLog(level, marker, format, MessageFormatter.getThrowableCandidate(arguments));
    super.formatLog(level, marker, format, arguments);
  }

  @Override
  protected void log(LogLevel level, Marker marker, String msg, Throwable t) {
    telemetryLog(level, marker, msg, t);
    super.log(level, marker, msg, t);
  }

  private void telemetryLog(LogLevel level, Marker marker, String msgOrgFormat, Throwable t) {
    if (marker == LogCollector.EXCLUDE_TELEMETRY) {
      return;
    }
    // Report only messages with Throwable or explicitly marked with SEND_TELEMETRY.
    // This might be extended to error level without throwable.
    if (marker != LogCollector.SEND_TELEMETRY && (t == null || t instanceof OutOfMemoryError)) {
      return;
    }
    LogCollector.get().addLogMessage(level.name(), msgOrgFormat, t);
  }

  private Throwable getIfThrowable(final Object obj) {
    return obj instanceof Throwable ? (Throwable) obj : null;
  }
}

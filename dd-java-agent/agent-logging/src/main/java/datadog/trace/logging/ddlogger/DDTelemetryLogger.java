package datadog.trace.logging.ddlogger;

import datadog.trace.api.LogCollector;
import datadog.trace.api.Platform;
import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LoggerHelper;
import org.slf4j.Marker;

public class DDTelemetryLogger extends DDLogger {

  public DDTelemetryLogger(LoggerHelper helper, String name) {
    super(helper, name);
  }

  @Override
  protected void alwaysLog(LogLevel level, Marker marker, String format, String msg, Throwable t) {
    super.alwaysLog(level, marker, format, msg, t);
    if (!Platform.isNativeImageBuilder()) {
      sendToTelemetry(level, marker, format, msg, t);
    }
  }

  private void sendToTelemetry(
      LogLevel level, Marker marker, String format, String msg, Throwable t) {
    // We report only messages with Throwable or explicitly marked with SEND_TELEMETRY
    if (t != null || marker == LogCollector.SEND_TELEMETRY) {
      // We are scrubbing all data from messages, and only send static (compile time)
      // log messages, plus redacted stack traces.
      if (format != null) {
        LogCollector.get().addLogMessage(level.name(), format, t);
      } else if (msg != null) {
        LogCollector.get().addLogMessage(level.name(), msg, t);
      }
    }
  }
}

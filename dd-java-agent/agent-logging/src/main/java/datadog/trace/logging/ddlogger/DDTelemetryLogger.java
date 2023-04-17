package datadog.trace.logging.ddlogger;

import datadog.trace.api.LogCollector;
import datadog.trace.api.Platform;
import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LoggerHelperFactory;
import org.slf4j.Marker;

public class DDTelemetryLogger extends DDLogger {

  public DDTelemetryLogger(LoggerHelperFactory helperFactory, String name) {
    super(helperFactory, name);
  }

  @Override
  protected void alwaysLog(LogLevel level, Marker marker, String format, String msg, Throwable t) {
    super.alwaysLog(level, marker, format, msg, t);
    sendToTelemetry(level, marker, format, msg, t);
  }

  private void sendToTelemetry(
      LogLevel level, Marker marker, String format, String msg, Throwable t) {
    if (Platform.isNativeImageBuilder()) {
      return;
    }

    if (!LogCollector.get().isEnabled()) {
      return;
    }

    // We report only messages with Throwable or explicitly marked with SEND_TELEMETRY
    if (t != null || marker == LogCollector.SEND_TELEMETRY) {
      if (level == LogLevel.DEBUG) {
        // For the "debug", we don't want to scrub data there generally,
        // as that's kind of the whole point, we already shouldn't be logging things like API keys
        // or tokens
        if (msg != null) {
          LogCollector.get().addLogMessage(level.name(), msg, t);
        }
      } else if (level == LogLevel.WARN || level == LogLevel.ERROR) {
        // For "errors" and "warnings", we are going to scrub all data from messages,
        // we are only send static (compile time) log messages, plus redacted stack traces.
        if (format != null) {
          LogCollector.get().addLogMessage(level.name(), format, t);
        } else if (msg != null) {
          LogCollector.get().addLogMessage(level.name(), msg, t);
        }
      }
    }
  }
}

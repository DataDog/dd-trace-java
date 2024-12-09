package datadog.trace.logging.simplelogger;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LoggerHelper;
import org.slf4j.Marker;

/**
 * A {@link LoggerHelper} that logs in a way compatible with the {@code SimpleLogger} from SLF4J.
 */
class SLCompatHelper extends LoggerHelper {
  private final String logName;
  private final LogLevel logLevel;
  private final SLCompatSettings settings;

  SLCompatHelper(String name, SLCompatSettings settings) {
    this(settings.logNameForName(name), settings.logLevelForName(name), settings);
  }

  SLCompatHelper(String logName, LogLevel logLevel, SLCompatSettings settings) {
    this.logName = logName;
    this.logLevel = logLevel;
    this.settings = settings;
  }

  @Override
  public boolean enabled(LogLevel level, Marker marker) {
    // Due to limited Marker support we assume it's depends on LogLevel only
    return level.isEnabled(this.logLevel);
  }

  @Override
  public void log(LogLevel level, Marker marker, String message, Throwable t) {
    long timeMillis = Integer.MIN_VALUE;
    if (settings.showDateTime) {
      timeMillis = System.currentTimeMillis();
    }
    log(level, marker, SLCompatFactory.START_TIME, timeMillis, message, t);
  }

  void log(
      LogLevel level,
      Marker marker,
      long startTimeMillis,
      long timeMillis,
      String message,
      Throwable t) {
    String threadName = null;
    if (settings.showThreadName) {
      threadName = Thread.currentThread().getName();
    }
    log(level, marker, startTimeMillis, timeMillis, threadName, message, t);
  }

  void log(
      LogLevel level,
      Marker marker,
      long startTimeMillis,
      long timeMillis,
      String threadName,
      String message,
      Throwable t) {
    StringBuilder buf = new StringBuilder(32);

    if (timeMillis >= 0 && settings.showDateTime) {
      settings.dateTimeFormatter.appendFormattedDate(buf, timeMillis, startTimeMillis);
      buf.append(' ');
    }

    if (settings.showThreadName && threadName != null) {
      buf.append('[');
      buf.append(threadName);
      buf.append("] ");
    }

    if (settings.levelInBrackets) {
      buf.append('[');
    }

    if (settings.warnLevelString != null && level == LogLevel.WARN) {
      buf.append(settings.warnLevelString);
    } else if (marker != null) {
      buf.append(marker.getName());
    } else {
      buf.append(level.name());
    }
    if (settings.levelInBrackets) {
      buf.append(']');
    }
    buf.append(' ');

    if (logName.length() > 0) {
      buf.append(logName).append(" - ");
    }

    buf.append(message);

    if (settings.embedException) {
      embedException(buf, t);
    }

    settings.printStream.println(buf.toString());
    if (!settings.embedException && t != null) {
      t.printStackTrace(settings.printStream);
    }
  }

  private void embedException(StringBuilder buf, Throwable t) {
    if (t != null) {
      buf.append(" [exception:");
      buf.append(t.toString());
      buf.append('.');
      for (StackTraceElement element : t.getStackTrace()) {
        buf.append(" at ");
        buf.append(element.toString());
      }
      buf.append(']');
    }
  }
}

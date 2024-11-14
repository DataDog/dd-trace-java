package datadog.trace.logging.simplelogger;

import static datadog.trace.util.Strings.escapeToJson;

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
    System.out.println("CTE settings.jsonEnabled: " + settings.jsonEnabled);
    if (settings.jsonEnabled) {
      logJson(level, marker, SLCompatFactory.START_TIME, timeMillis, message, t);
    } else {
      log(level, marker, SLCompatFactory.START_TIME, timeMillis, message, t);
    }
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

    if (!logName.isEmpty()) {
      buf.append(logName).append(" - ");
    }

    buf.append(message);

    if (settings.embedException && t != null) {
      embedException(buf, t);
    }

    settings.printStream.println(buf);
    if (!settings.embedException && t != null) {
      t.printStackTrace(settings.printStream);
    }
  }

  private void embedException(StringBuilder buf, Throwable t) {
    buf.append(" [exception:");
    buf.append(t.toString());
    buf.append(".");
    for (StackTraceElement element : t.getStackTrace()) {
      buf.append(" at ");
      buf.append(element.toString());
    }
    buf.append("]");
  }

  void logJson(
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
    logJson(level, marker, startTimeMillis, timeMillis, threadName, message, t);
  }

  void logJson(
      LogLevel level,
      Marker marker,
      long startTimeMillis,
      long timeMillis,
      String threadName,
      String message,
      Throwable t) {
    StringBuilder buf = new StringBuilder(32);

    buf.append("{");

    if (timeMillis >= 0 && settings.showDateTime) {
      embedJsonKey(buf, "time");
      settings.dateTimeFormatter.appendFormattedDate(buf, timeMillis, startTimeMillis);
      buf.append("\",");
    }

    if (settings.showThreadName && threadName != null) {
      embedJson(buf, "threadName", threadName, true);
    }

    embedJsonKey(buf, "level");

    if (settings.warnLevelString != null && level == LogLevel.WARN) {
      embedJsonValue(buf, wrappedValueWithBracketsIfRequested(settings.warnLevelString), true);
    } else if (marker != null) {
      embedJsonValue(buf, wrappedValueWithBracketsIfRequested(marker.getName()), true);
    } else {
      embedJsonValue(buf, wrappedValueWithBracketsIfRequested(level.name()), true);
    }

    if (!logName.isEmpty()) {
      embedJson(buf, "loggerName", logName, true);
    }
    embedJson(buf, "message", message, false);

    if (t != null) {
      buf.append(",");
      embedExceptionJson(buf, t);
    }

    buf.append("}");

    settings.printStream.println(buf);
  }

  private void embedJson(StringBuilder buf, String key, String value, boolean withComma) {
    embedJsonKey(buf, key);
    embedJsonValue(buf, value, withComma);
  }

  private String wrappedValueWithBracketsIfRequested(String value) {
    return settings.levelInBrackets ? '[' + value + ']' : value;
  }

  private void embedJsonKey(StringBuilder buf, String key) {
    buf.append("\"").append(escapeToJson(key)).append("\":\"");
  }

  private void embedJsonValue(StringBuilder buf, String value, boolean withComma) {
    buf.append(escapeToJson(value)).append("\"");
    if (withComma) {
      buf.append(",");
    }
  }

  private void embedExceptionJson(StringBuilder buf, Throwable t) {
    buf.append("\"exception\":{");
    embedJson(buf, "message", escapeToJson(t.getMessage()), true);
    int length = t.getStackTrace().length;
    if (length > 0) {
      buf.append("\"stackTrace\":[\"");
      int count = 0;
      for (StackTraceElement element : t.getStackTrace()) {
        count += 1;
        buf.append(escapeToJson(element.toString()));
        if (count != length) {
          buf.append("\",\"");
        }
      }
      buf.append("\"]");
    }

    buf.append("}");
  }
}

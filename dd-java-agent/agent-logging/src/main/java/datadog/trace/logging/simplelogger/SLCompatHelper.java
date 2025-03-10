package datadog.trace.logging.simplelogger;

import datadog.json.JsonWriter;
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

    JsonWriter writer = new JsonWriter();
    writer.beginObject();
    writer.name("origin").value("dd.trace");

    if (timeMillis >= 0 && settings.showDateTime) {
      writer.name("date");
      StringBuilder buf = new StringBuilder(32);
      settings.dateTimeFormatter.appendFormattedDate(buf, timeMillis, startTimeMillis);
      writer.value(buf.toString());
    }

    if (settings.showThreadName && threadName != null) {
      writer.name("logger.thread_name").value(threadName);
    }

    writer.name("level");

    if (settings.warnLevelString != null && level == LogLevel.WARN) {
      writer.value(wrappedValueWithBracketsIfRequested(settings.warnLevelString));
    } else if (marker != null) {
      writer.value(wrappedValueWithBracketsIfRequested(marker.getName()));
    } else {
      writer.value(wrappedValueWithBracketsIfRequested(level.name()));
    }

    if (!logName.isEmpty()) {
      writer.name("logger.name").value(logName);
    }
    writer.name("message").value(message);

    if (t != null) {
      embedExceptionJson(writer, t);
    }
    writer.endObject();
    settings.printStream.println(writer);
  }

  private String wrappedValueWithBracketsIfRequested(String value) {
    return settings.levelInBrackets ? '[' + value + ']' : value;
  }

  private void embedExceptionJson(JsonWriter writer, Throwable t) {
    writer.name("exception");
    writer.beginObject();
    writer.name("message").value(t.getMessage());
    if (t.getStackTrace().length > 0) {
      writer.name("stackTrace");
      writer.beginArray();
      for (StackTraceElement element : t.getStackTrace()) {
        writer.value(element.toString());
      }
      writer.endArray();
    }
    writer.endObject();
  }
}

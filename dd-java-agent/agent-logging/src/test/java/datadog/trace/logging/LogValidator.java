package datadog.trace.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import org.slf4j.Marker;

/** Captures logger output and validates it line by line. */
public class LogValidator {
  private final String name;
  private final ByteArrayOutputStream output;

  private LogValidator(String name, ByteArrayOutputStream output) {
    this.name = name;
    this.output = output;
  }

  private LogValidator(String name) {
    this(name, new ByteArrayOutputStream());
  }

  public static LogValidator createValidator(String loggerName) {
    return new LogValidator(loggerName);
  }

  public static Exception exception(String exceptionMessage) {
    return new NoStackException(exceptionMessage);
  }

  public OutputStream getOutputStream() {
    return output;
  }

  public LogValidator withName(String name) {
    return new LogValidator(name, output);
  }

  public void log(boolean enabled, String level, String marker, String msg) {
    log(enabled, level, marker, msg, null);
  }

  public void log(
      boolean enabled, String level, String marker, String msg, String exceptionMessage) {
    String current = output.toString();
    String expected = "";
    if (enabled) {
      expected = (marker == null ? level : marker) + " " + name + " - " + msg + "\n";
      if (exceptionMessage != null) {
        expected += NoStackException.class.getName() + ": " + exceptionMessage + "\n";
      }
    }
    assertEquals(expected, current);
    output.reset();
  }

  public void trace(boolean enabled, Marker marker, String msg) {
    log(enabled, "TRACE", marker.getName(), msg);
  }

  public void trace(boolean enabled, String msg) {
    log(enabled, "TRACE", null, msg);
  }

  public void trace(boolean enabled, String msg, String exceptionMessage) {
    log(enabled, "TRACE", null, msg, exceptionMessage);
  }

  public void debug(boolean enabled, Marker marker, String msg) {
    log(enabled, "DEBUG", marker.getName(), msg);
  }

  public void debug(boolean enabled, String msg) {
    log(enabled, "DEBUG", null, msg);
  }

  public void debug(boolean enabled, String msg, String exceptionMessage) {
    log(enabled, "DEBUG", null, msg, exceptionMessage);
  }

  public void info(boolean enabled, Marker marker, String msg) {
    log(enabled, "INFO", marker.getName(), msg);
  }

  public void info(boolean enabled, String msg) {
    log(enabled, "INFO", null, msg);
  }

  public void info(boolean enabled, String msg, String exceptionMessage) {
    log(enabled, "INFO", null, msg, exceptionMessage);
  }

  public void warn(boolean enabled, Marker marker, String msg) {
    log(enabled, "WARN", marker.getName(), msg);
  }

  public void warn(boolean enabled, String msg) {
    log(enabled, "WARN", null, msg);
  }

  public void warn(boolean enabled, Marker marker, String msg, String exceptionMessage) {
    log(enabled, "WARN", marker.getName(), msg, exceptionMessage);
  }

  public void warn(boolean enabled, String msg, String exceptionMessage) {
    log(enabled, "WARN", null, msg, exceptionMessage);
  }

  public void error(boolean enabled, Marker marker, String msg) {
    log(enabled, "ERROR", marker.getName(), msg);
  }

  public void error(boolean enabled, String msg) {
    log(enabled, "ERROR", null, msg);
  }

  public void error(boolean enabled, String msg, String exceptionMessage) {
    log(enabled, "ERROR", null, msg, exceptionMessage);
  }

  public void nothing() {
    log(false, "IGNORED", null, null, null);
  }
}

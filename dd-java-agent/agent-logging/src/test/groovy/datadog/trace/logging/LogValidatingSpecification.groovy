package datadog.trace.logging

import org.slf4j.Marker
import spock.lang.Specification

abstract class LogValidatingSpecification extends Specification {
  LogValidator createValidator(String loggerName) {
    new LogValidator(loggerName)
  }

  Exception exception(emsg) {
    new NoStackException(emsg)
  }

  private static validateLogLine(LogValidator validator, boolean enabled, String level, String marker, String msg, String emsg) {
    def current = validator.outputStream.toString()
    def expected = ""
    if (enabled) {
      expected = marker == null ? "$level ${validator.name} - $msg\n" : "$marker ${validator.name} - $msg\n"
      expected = emsg == null ? expected : "$expected${NoStackException.getName()}: $emsg\n"
    }
    assert current == expected
    validator.output.reset()
  }

  class LogValidator {
    private final String name
    private final ByteArrayOutputStream output

    private LogValidator(String name, ByteArrayOutputStream output) {
      this.name = name
      this.output = output
    }

    private LogValidator(String name) {
      this(name, new ByteArrayOutputStream())
    }

    OutputStream getOutputStream() {
      this.output
    }

    LogValidator withName(String name) {
      new LogValidator(name, this.output)
    }

    void log(boolean enabled, String level, String marker, String msg) {
      LogValidatingSpecification.validateLogLine(this, enabled, level, marker, msg, null)
    }

    void log(boolean enabled, String level, String marker, String msg, String emsg) {
      LogValidatingSpecification.validateLogLine(this, enabled, level, marker, msg, emsg)
    }

    void trace(boolean enabled, Marker marker, String msg) {
      log(enabled, "TRACE", marker.getName(), msg)
    }

    void trace(boolean enabled, String msg) {
      log(enabled, "TRACE", null, msg)
    }

    void trace(boolean enabled, String msg, String emsg) {
      log(enabled, "TRACE", null, msg, emsg)
    }

    void debug(boolean enabled, Marker marker, String msg) {
      log(enabled, "DEBUG", marker.getName(), msg)
    }

    void debug(boolean enabled, String msg) {
      log(enabled, "DEBUG", null, msg)
    }

    void debug(boolean enabled, String msg, String emsg) {
      log(enabled, "DEBUG", null, msg, emsg)
    }

    void info(boolean enabled, Marker marker, String msg) {
      log(enabled, "INFO", marker.getName(), msg)
    }

    void info(boolean enabled, String msg) {
      log(enabled, "INFO", null, msg)
    }

    void info(boolean enabled, String msg, String emsg) {
      log(enabled, "INFO", null, msg, emsg)
    }

    void warn(boolean enabled, Marker marker, String msg) {
      log(enabled, "WARN", marker.getName(), msg)
    }

    void warn(boolean enabled, String msg) {
      log(enabled, "WARN", null, msg)
    }

    void warn(boolean enabled, Marker marker, String msg, String emsg) {
      log(enabled, "WARN", marker.getName(), msg, emsg)
    }

    void warn(boolean enabled, String msg, String emsg) {
      log(enabled, "WARN", null, msg, emsg)
    }

    void error(boolean enabled, Marker marker, String msg) {
      log(enabled, "ERROR", marker.getName(), msg)
    }

    void error(boolean enabled, String msg) {
      log(enabled, "ERROR", null, msg)
    }

    void error(boolean enabled, String msg, String emsg) {
      log(enabled, "ERROR", null, msg, emsg)
    }

    void nothing() {
      log(false, "IGNORED", null, null, null)
    }
  }

  static class NoStackException extends Exception {
    NoStackException(String message) {
      super(message, null, false, false)
    }
  }
}

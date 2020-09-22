package datadog.trace.logging

import spock.lang.Specification

abstract class LogValidatingSpecification extends Specification {
  LogValidator createValidator(String loggerName) {
    new LogValidator(loggerName)
  }

  Exception exception(emsg) {
    new NoStackException(emsg)
  }

  private static validateLogLine(LogValidator validator, boolean enabled, String level, String msg, String emsg) {
    def current = validator.outputStream.toString()
    def expected = ""
    if (enabled) {
      expected = "$level ${validator.name} - $msg\n"
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

    void log(boolean enabled, String level, String msg) {
      LogValidatingSpecification.validateLogLine(this, enabled, level, msg, null)
    }

    void log(boolean enabled, String level, String msg, String emsg) {
      LogValidatingSpecification.validateLogLine(this, enabled, level, msg, emsg)
    }

    void trace(boolean enabled, String msg) {
      log(enabled, "TRACE", msg)
    }

    void trace(boolean enabled, String msg, String emsg) {
      log(enabled, "TRACE", msg, emsg)
    }

    void debug(boolean enabled, String msg) {
      log(enabled, "DEBUG", msg)
    }

    void debug(boolean enabled, String msg, String emsg) {
      log(enabled, "DEBUG", msg, emsg)
    }

    void info(boolean enabled, String msg) {
      log(enabled, "INFO", msg)
    }

    void info(boolean enabled, String msg, String emsg) {
      log(enabled, "INFO", msg, emsg)
    }

    void warn(boolean enabled, String msg) {
      log(enabled, "WARN", msg)
    }

    void warn(boolean enabled, String msg, String emsg) {
      log(enabled, "WARN", msg, emsg)
    }

    void error(boolean enabled, String msg) {
      log(enabled, "ERROR", msg)
    }

    void error(boolean enabled, String msg, String emsg) {
      log(enabled, "ERROR", msg, emsg)
    }

    void nothing() {
      log(false, "IGNORED", null, null)
    }
  }

  static class NoStackException extends Exception {
    NoStackException(String message) {
      super(message, null, false, false)
    }
  }
}

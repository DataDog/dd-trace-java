package datadog.trace.logging.ddlogger;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LoggerHelper;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

/** Implementation of org.slf4j.Logger. Delegates actual rendering to {@link LoggerHelper}. */
public class DDLogger implements Logger {

  private final String name;
  private final LoggerHelper helper;

  public DDLogger(LoggerHelper helper, String name) {
    this.name = name;
    this.helper = helper;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean isTraceEnabled() {
    return helper.enabled(LogLevel.TRACE, null);
  }

  @Override
  public void trace(String msg) {
    log(LogLevel.TRACE, null, msg, null);
  }

  @Override
  public void trace(String format, Object arg) {
    formatLog(LogLevel.TRACE, null, format, arg);
  }

  @Override
  public void trace(String format, Object arg1, Object arg2) {
    formatLog(LogLevel.TRACE, null, format, arg1, arg2);
  }

  @Override
  public void trace(String format, Object... arguments) {
    formatLog(LogLevel.TRACE, null, format, arguments);
  }

  @Override
  public void trace(String msg, Throwable t) {
    log(LogLevel.TRACE, null, msg, t);
  }

  @Override
  public boolean isTraceEnabled(Marker marker) {
    return helper.enabled(LogLevel.TRACE, marker);
  }

  @Override
  public void trace(Marker marker, String msg) {
    log(LogLevel.TRACE, marker, msg, null);
  }

  @Override
  public void trace(Marker marker, String format, Object arg) {
    formatLog(LogLevel.TRACE, marker, format, arg);
  }

  @Override
  public void trace(Marker marker, String format, Object arg1, Object arg2) {
    formatLog(LogLevel.TRACE, marker, format, arg1, arg2);
  }

  @Override
  public void trace(Marker marker, String format, Object... arguments) {
    formatLog(LogLevel.TRACE, marker, format, arguments);
  }

  @Override
  public void trace(Marker marker, String msg, Throwable t) {
    log(LogLevel.TRACE, marker, msg, t);
  }

  @Override
  public boolean isDebugEnabled() {
    return helper.enabled(LogLevel.DEBUG, null);
  }

  @Override
  public void debug(String msg) {
    log(LogLevel.DEBUG, null, msg, null);
  }

  @Override
  public void debug(String format, Object arg) {
    formatLog(LogLevel.DEBUG, null, format, arg);
  }

  @Override
  public void debug(String format, Object arg1, Object arg2) {
    formatLog(LogLevel.DEBUG, null, format, arg1, arg2);
  }

  @Override
  public void debug(String format, Object... arguments) {
    formatLog(LogLevel.DEBUG, null, format, arguments);
  }

  @Override
  public void debug(String msg, Throwable t) {
    log(LogLevel.DEBUG, null, msg, t);
  }

  @Override
  public boolean isDebugEnabled(Marker marker) {
    return helper.enabled(LogLevel.DEBUG, marker);
  }

  @Override
  public void debug(Marker marker, String msg) {
    log(LogLevel.DEBUG, marker, msg, null);
  }

  @Override
  public void debug(Marker marker, String format, Object arg) {
    formatLog(LogLevel.DEBUG, marker, format, arg);
  }

  @Override
  public void debug(Marker marker, String format, Object arg1, Object arg2) {
    formatLog(LogLevel.DEBUG, marker, format, arg1, arg2);
  }

  @Override
  public void debug(Marker marker, String format, Object... arguments) {
    formatLog(LogLevel.DEBUG, marker, format, arguments);
  }

  @Override
  public void debug(Marker marker, String msg, Throwable t) {
    log(LogLevel.DEBUG, marker, msg, t);
  }

  @Override
  public boolean isInfoEnabled() {
    return helper.enabled(LogLevel.INFO, null);
  }

  @Override
  public void info(String msg) {
    log(LogLevel.INFO, null, msg, null);
  }

  @Override
  public void info(String format, Object arg) {
    formatLog(LogLevel.INFO, null, format, arg);
  }

  @Override
  public void info(String format, Object arg1, Object arg2) {
    formatLog(LogLevel.INFO, null, format, arg1, arg2);
  }

  @Override
  public void info(String format, Object... arguments) {
    formatLog(LogLevel.INFO, null, format, arguments);
  }

  @Override
  public void info(String msg, Throwable t) {
    log(LogLevel.INFO, null, msg, t);
  }

  @Override
  public boolean isInfoEnabled(Marker marker) {
    return helper.enabled(LogLevel.INFO, marker);
  }

  @Override
  public void info(Marker marker, String msg) {
    log(LogLevel.INFO, marker, msg, null);
  }

  @Override
  public void info(Marker marker, String format, Object arg) {
    formatLog(LogLevel.INFO, marker, format, arg);
  }

  @Override
  public void info(Marker marker, String format, Object arg1, Object arg2) {
    formatLog(LogLevel.INFO, marker, format, arg1, arg2);
  }

  @Override
  public void info(Marker marker, String format, Object... arguments) {
    formatLog(LogLevel.INFO, marker, format, arguments);
  }

  @Override
  public void info(Marker marker, String msg, Throwable t) {
    log(LogLevel.INFO, marker, msg, t);
  }

  @Override
  public boolean isWarnEnabled() {
    return helper.enabled(LogLevel.WARN, null);
  }

  @Override
  public void warn(String msg) {
    log(LogLevel.WARN, null, msg, null);
  }

  @Override
  public void warn(String format, Object arg) {
    formatLog(LogLevel.WARN, null, format, arg);
  }

  @Override
  public void warn(String format, Object arg1, Object arg2) {
    formatLog(LogLevel.WARN, null, format, arg1, arg2);
  }

  @Override
  public void warn(String format, Object... arguments) {
    formatLog(LogLevel.WARN, null, format, arguments);
  }

  @Override
  public void warn(String msg, Throwable t) {
    log(LogLevel.WARN, null, msg, t);
  }

  @Override
  public boolean isWarnEnabled(Marker marker) {
    return helper.enabled(LogLevel.WARN, marker);
  }

  @Override
  public void warn(Marker marker, String msg) {
    log(LogLevel.WARN, marker, msg, null);
  }

  @Override
  public void warn(Marker marker, String format, Object arg) {
    formatLog(LogLevel.WARN, marker, format, arg);
  }

  @Override
  public void warn(Marker marker, String format, Object arg1, Object arg2) {
    formatLog(LogLevel.WARN, marker, format, arg1, arg2);
  }

  @Override
  public void warn(Marker marker, String format, Object... arguments) {
    formatLog(LogLevel.WARN, marker, format, arguments);
  }

  @Override
  public void warn(Marker marker, String msg, Throwable t) {
    log(LogLevel.WARN, marker, msg, t);
  }

  @Override
  public boolean isErrorEnabled() {
    return helper.enabled(LogLevel.ERROR, null);
  }

  @Override
  public void error(String msg) {
    log(LogLevel.ERROR, null, msg, null);
  }

  @Override
  public void error(String format, Object arg) {
    formatLog(LogLevel.ERROR, null, format, arg);
  }

  @Override
  public void error(String format, Object arg1, Object arg2) {
    formatLog(LogLevel.ERROR, null, format, arg1, arg2);
  }

  @Override
  public void error(String format, Object... arguments) {
    formatLog(LogLevel.ERROR, null, format, arguments);
  }

  @Override
  public void error(String msg, Throwable t) {
    log(LogLevel.ERROR, null, msg, t);
  }

  @Override
  public boolean isErrorEnabled(Marker marker) {
    return helper.enabled(LogLevel.ERROR, marker);
  }

  @Override
  public void error(Marker marker, String msg) {
    log(LogLevel.ERROR, marker, msg, null);
  }

  @Override
  public void error(Marker marker, String format, Object arg) {
    formatLog(LogLevel.ERROR, marker, format, arg);
  }

  @Override
  public void error(Marker marker, String format, Object arg1, Object arg2) {
    formatLog(LogLevel.ERROR, marker, format, arg1, arg2);
  }

  @Override
  public void error(Marker marker, String format, Object... arguments) {
    formatLog(LogLevel.ERROR, marker, format, arguments);
  }

  @Override
  public void error(Marker marker, String msg, Throwable t) {
    log(LogLevel.ERROR, marker, msg, t);
  }

  public void formatLog(LogLevel level, Marker marker, String format, Object arg) {
    if (!helper.enabled(level, marker)) {
      return;
    }

    FormattingTuple tuple = MessageFormatter.format(format, arg);
    alwaysLog(level, marker, tuple.getMessage(), tuple.getThrowable());
  }

  public void formatLog(LogLevel level, Marker marker, String format, Object arg1, Object arg2) {
    if (!helper.enabled(level, marker)) {
      return;
    }

    FormattingTuple tuple = MessageFormatter.format(format, arg1, arg2);
    alwaysLog(level, marker, tuple.getMessage(), tuple.getThrowable());
  }

  public void formatLog(LogLevel level, Marker marker, String format, Object... arguments) {
    if (!helper.enabled(level, marker)) {
      return;
    }

    FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
    alwaysLog(level, marker, tuple.getMessage(), tuple.getThrowable());
  }

  protected void log(LogLevel level, Marker marker, String msg, Throwable t) {
    if (!helper.enabled(level, marker)) {
      return;
    }
    helper.log(level, marker, msg, t);
  }

  private void alwaysLog(LogLevel level, Marker marker, String msg, Throwable t) {
    helper.log(level, marker, msg, t);
  }
}

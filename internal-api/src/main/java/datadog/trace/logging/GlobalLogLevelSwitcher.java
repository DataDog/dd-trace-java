package datadog.trace.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalLogLevelSwitcher implements LogLevelSwitcher {
  private static volatile LogLevelSwitcher INSTANCE = null;

  public static LogLevelSwitcher get() {
    LogLevelSwitcher switcher = INSTANCE;
    if (switcher == null) {
      ILoggerFactory factory = LoggerFactory.getILoggerFactory();
      INSTANCE = switcher = new GlobalLogLevelSwitcher(factory);
    }
    return switcher;
  }

  private final Logger log;
  private final LogLevelSwitcher delegate;

  GlobalLogLevelSwitcher(ILoggerFactory factory) {
    log = factory.getLogger(GlobalLogLevelSwitcher.class.getName());
    if (factory instanceof LogLevelSwitcher) {
      delegate = (LogLevelSwitcher) factory;
    } else {
      log.error(
          "Unable to find global log level switcher, found {}", factory.getClass().getSimpleName());
      delegate = null;
    }
  }

  @Override
  public void switchLevel(LogLevel level) {
    if (delegate != null) {
      delegate.switchLevel(level);
    }
  }

  @Override
  public void restore() {
    if (delegate != null) {
      delegate.restore();
    }
  }

  @Override
  public void reinitialize() {
    if (delegate != null) {
      delegate.reinitialize();
    }
  }
}

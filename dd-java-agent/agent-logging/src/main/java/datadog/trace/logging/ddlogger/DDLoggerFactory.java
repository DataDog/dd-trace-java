package datadog.trace.logging.ddlogger;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogLevelSwitcher;
import datadog.trace.logging.LoggerHelperFactory;
import datadog.trace.logging.simplelogger.SLCompatFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

public class DDLoggerFactory implements ILoggerFactory, LogLevelSwitcher {

  private final AtomicReference<LogLevel> override = new AtomicReference<>();
  private SwitchableLogLevelFactory helperFactory = null;

  public DDLoggerFactory() {}

  // Only used for testing
  public DDLoggerFactory(SwitchableLogLevelFactory helperFactory) {
    this.helperFactory = helperFactory;
  }

  @Override
  public Logger getLogger(String name) {
    LoggerHelperFactory c = helperFactory;
    if (c == null) {
      c = helperFactory = new SwitchableLogLevelFactory(override, new SLCompatFactory());
    }
    return new DDLogger(c, name);
  }

  @Override
  public void switchLevel(LogLevel level) {
    helperFactory.switchLevel(level);
  }

  @Override
  public void restore() {
    helperFactory.restore();
  }
}

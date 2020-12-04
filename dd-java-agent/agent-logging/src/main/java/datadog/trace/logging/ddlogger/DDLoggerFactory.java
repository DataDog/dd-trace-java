package datadog.trace.logging.ddlogger;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogLevelSwitcher;
import datadog.trace.logging.LoggingSettingsDescription;
import datadog.trace.logging.simplelogger.SLCompatFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

public class DDLoggerFactory implements ILoggerFactory, LogLevelSwitcher {

  private volatile SwitchableLogLevelFactory helperFactory = null;

  public DDLoggerFactory() {}

  // Only used for testing
  public DDLoggerFactory(SwitchableLogLevelFactory helperFactory) {
    this.helperFactory = helperFactory;
  }

  private SwitchableLogLevelFactory getHelperFactory() {
    SwitchableLogLevelFactory factory = helperFactory;
    if (factory == null) {
      synchronized (this) {
        factory = helperFactory;
        if (factory == null) {
          factory = helperFactory = new SwitchableLogLevelFactory(new SLCompatFactory());
          LoggingSettingsDescription.setDescription(factory.getSettingsDescription());
        }
      }
    }
    return factory;
  }

  @Override
  public Logger getLogger(String name) {
    return new DDLogger(getHelperFactory(), name);
  }

  @Override
  public void switchLevel(LogLevel level) {
    getHelperFactory().switchLevel(level);
  }

  @Override
  public void restore() {
    getHelperFactory().restore();
  }
}

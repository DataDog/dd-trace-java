package datadog.trace.logging.ddlogger;

import datadog.trace.api.Platform;
import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogLevelSwitcher;
import datadog.trace.logging.LoggingSettingsDescription;
import datadog.trace.logging.simplelogger.SLCompatFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

public class DDLoggerFactory implements ILoggerFactory, LogLevelSwitcher {

  private volatile SwitchableLogLevelFactory helperFactory = null;
  private final boolean telemetryLogCollectionEnabled = getLogCollectionEnabled(false);

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
    // Native image builder can't see telemetry and won't use it
    if (Platform.isNativeImageBuilder() && !telemetryLogCollectionEnabled) {
      return new DDLogger(getHelperFactory(), name);
    } else {
      return new DDTelemetryLogger(getHelperFactory(), name);
    }
  }

  @Override
  public void switchLevel(LogLevel level) {
    getHelperFactory().switchLevel(level);
  }

  @Override
  public void restore() {
    getHelperFactory().restore();
  }

  // DDLoggerFactory can be called at very early stage, before Config loaded
  // So to get property/env we use this custom fucntion
  private boolean getLogCollectionEnabled(boolean defaultValue) {
    String value = System.getProperty("dd.telemetry.log.collection.enabled");
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    value = System.getProperty("DD_TELEMETRY_LOG_COLLECTION_ENABLED");
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    return defaultValue;
  }
}

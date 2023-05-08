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
  private final boolean telemetryLogCollectionEnabled;

  public DDLoggerFactory() {
    String value = System.getProperty("dd.telemetry.log.collection.enabled");
    boolean propValue = Boolean.parseBoolean(value);

    value = System.getenv("DD_TELEMETRY_LOG_COLLECTION_ENABLED");
    boolean envValue = Boolean.parseBoolean(value);

    telemetryLogCollectionEnabled = propValue | envValue;
  }

  // Only used for testing
  public DDLoggerFactory(SwitchableLogLevelFactory helperFactory) {
    this.helperFactory = helperFactory;
    this.telemetryLogCollectionEnabled = false;
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
}

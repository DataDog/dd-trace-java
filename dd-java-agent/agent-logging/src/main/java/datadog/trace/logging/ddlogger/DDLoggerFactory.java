package datadog.trace.logging.ddlogger;

import datadog.environment.SystemProperties;
import datadog.trace.api.Platform;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogLevelSwitcher;
import datadog.trace.logging.LoggerHelper;
import datadog.trace.logging.LoggerHelperFactory;
import datadog.trace.logging.LoggingSettingsDescription;
import datadog.trace.logging.simplelogger.SLCompatFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;

public class DDLoggerFactory implements ILoggerFactory, LogLevelSwitcher {

  private final boolean telemetryLogCollectionEnabled = isLogCollectionEnabled();

  private volatile LoggerHelperFactory helperFactory = null;
  private volatile LogLevel override = null;

  public DDLoggerFactory() {}

  // Only used for testing
  public DDLoggerFactory(LoggerHelperFactory helperFactory) {
    this.helperFactory = helperFactory;
  }

  @Override
  public void switchLevel(LogLevel level) {
    override = level;
  }

  @Override
  public void restore() {
    override = null;
  }

  final class HelperWrapper extends LoggerHelper {
    private final LoggerHelper delegate;

    private HelperWrapper(LoggerHelper delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean enabled(LogLevel level, Marker marker) {
      LogLevel levelOverride = override;
      if (levelOverride != null) {
        return level.isEnabled(levelOverride);
      }

      return delegate.enabled(level, marker);
    }

    @Override
    public void log(LogLevel level, Marker marker, String message, Throwable t) {
      delegate.log(level, marker, message, t);
    }
  }

  @Override
  public Logger getLogger(String name) {
    LoggerHelper helper = getHelperFactory().loggerHelperForName(name);
    HelperWrapper helperWrapper = new HelperWrapper(helper);
    if (!telemetryLogCollectionEnabled || Platform.isNativeImageBuilder()) {
      return new DDLogger(helperWrapper, name);
    } else {
      return new DDTelemetryLogger(helperWrapper, name);
    }
  }

  private LoggerHelperFactory getHelperFactory() {
    LoggerHelperFactory factory = helperFactory;
    if (factory == null) {
      synchronized (this) {
        factory = helperFactory;
        if (factory == null) {
          factory = helperFactory = new SLCompatFactory();
          LoggingSettingsDescription.setDescription(factory.getSettingsDescription());
        }
      }
    }
    return factory;
  }

  @Override
  public void reinitialize() {
    helperFactory = null;
  }

  // DDLoggerFactory can be called at very early stage, before Config is loaded
  // So to get property/env we use this custom function
  private static boolean isLogCollectionEnabled() {
    return isFlagEnabled(
            "dd.instrumentation.telemetry.enabled", "DD_INSTRUMENTATION_TELEMETRY_ENABLED", true)
        && isFlagEnabled(
            "dd.telemetry.log-collection.enabled", "DD_TELEMETRY_LOG_COLLECTION_ENABLED", true);
  }

  private static boolean isFlagEnabled(
      final String systemProperty, final String envVar, final boolean defaultValue) {
    String value = SystemProperties.get(systemProperty);
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    value = ConfigHelper.env(envVar);
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    return defaultValue;
  }
}

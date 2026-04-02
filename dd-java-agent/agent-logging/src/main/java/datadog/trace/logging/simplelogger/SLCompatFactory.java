package datadog.trace.logging.simplelogger;

import datadog.trace.logging.LoggerHelper;
import datadog.trace.logging.LoggerHelperFactory;
import java.util.Map;
import java.util.Properties;

/**
 * A {@link LoggerHelperFactory} for {@link SLCompatHelper} that logs in a way compatible with the
 * {@code SimpleLogger} from SLF4J.
 *
 * <p>Tries to initialize lazily to mimic the behavior of {@code SimpleLogger} as much as possible.
 */
public final class SLCompatFactory extends LoggerHelperFactory {
  static final long START_TIME = System.currentTimeMillis();

  // DQH - ExceptionLogger isn't visible to this class, so hard-coding instead
  static final String EXCEPTION_LOGGER_NAME = "datadog.trace.bootstrap.ExceptionLogger";
  LoggerHelper cachedExceptionLoggerHelper;

  private static Properties getProperties() {
    try {
      return System.getProperties();
    } catch (SecurityException e) {
      return new Properties();
    }
  }

  private final Properties properties;
  private volatile SLCompatSettings lazySettings;

  public SLCompatFactory() {
    this(getProperties());
  }

  public SLCompatFactory(Properties properties) {
    this(properties, null);
  }

  public SLCompatFactory(Properties properties, SLCompatSettings settings) {
    this.properties = properties;
    this.lazySettings = settings;
  }

  private SLCompatSettings getSettings() {
    // This race condition is intentional and benign.
    SLCompatSettings settings = lazySettings;
    while (settings == null) {
      try {
        settings = lazySettings = new SLCompatSettings(properties);
      } catch (IllegalStateException e) {
        // This can only happen if there is a race within the constructor and multiple
        // threads try to create conflicting log files
      }
    }
    return settings;
  }

  @Override
  public LoggerHelper loggerHelperForName(String name) {
    if (EXCEPTION_LOGGER_NAME.equals(name)) {
      // DQH - This is ugly.  The exception propagation protection that handles
      // unexpected exceptions in instrumentation always calls LoggerFactory.getLogger.

      // getLogger is actually a factory method that always creates a new instance,
      // which can cause this method to become extremely hot -- leading to significant
      // amount of allocation and garbage collection.

      // To guard against that scenario, cache the ExceptionLogger LoggerHelper instance
      LoggerHelper helper = cachedExceptionLoggerHelper;
      if (helper != null) return helper;

      helper = createLoggerHelperForName(name);
      cachedExceptionLoggerHelper = helper;
      return helper;
    } else {
      return createLoggerHelperForName(name);
    }
  }

  private LoggerHelper createLoggerHelperForName(String name) {
    SLCompatSettings settings = getSettings();
    return new SLCompatHelper(name, settings);
  }

  @Override
  public Map<String, Object> getSettingsDescription() {
    return getSettings().getSettingsDescription();
  }
}

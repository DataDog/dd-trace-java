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
    SLCompatSettings settings = getSettings();
    return new SLCompatHelper(name, settings);
  }

  @Override
  public Map<String, Object> getSettingsDescription() {
    return getSettings().getSettingsDescription();
  }
}

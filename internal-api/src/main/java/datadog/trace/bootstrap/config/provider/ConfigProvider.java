package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.config.GeneralConfig.CONFIGURATION_FILE;

import datadog.trace.api.ConfigCollector;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigProvider {
  private static final class Singleton {
    private static final ConfigProvider INSTANCE = ConfigProvider.createDefault();
  }

  private static final Logger log = LoggerFactory.getLogger(ConfigProvider.class);

  protected final ConfigProvider.Source[] sources;

  private ConfigProvider(ConfigProvider.Source... sources) {
    this.sources = sources;
  }

  public final String getConfigFileStatus() {
    for (ConfigProvider.Source source : sources) {
      if (source instanceof PropertiesConfigSource) {
        String configFileStatus = ((PropertiesConfigSource) source).getConfigFileStatus();
        if (null != configFileStatus) {
          return configFileStatus;
        }
      }
    }
    return "no config file present";
  }

  public final String getString(String key) {
    return getString(key, null);
  }

  public final <T extends Enum<T>> T getEnum(String key, Class<T> enumType, T defaultValue) {
    String value = getString(key);
    if (null != value) {
      try {
        return Enum.valueOf(enumType, value);
      } catch (Exception ignoreAndUseDefault) {
        log.debug("failed to parse {} for {}, defaulting to {}", value, key, defaultValue);
      }
    }
    return defaultValue;
  }

  public final String getString(String key, String defaultValue, String... aliases) {
    for (ConfigProvider.Source source : sources) {
      String value = source.get(key, aliases);
      if (value != null) {
        return value;
      }
    }
    return defaultValue;
  }

  public final String getStringExcludingSource(
      String key,
      String defaultValue,
      Class<? extends ConfigProvider.Source> excludedSource,
      String... aliases) {
    for (ConfigProvider.Source source : sources) {
      if (excludedSource.isAssignableFrom(source.getClass())) {
        continue;
      }

      String value = source.get(key, aliases);
      if (value != null) {
        return value;
      }
    }
    return defaultValue;
  }

  public final boolean isSet(String key) {
    String value = getString(key);
    return value != null && !value.isEmpty();
  }

  public final Boolean getBoolean(String key) {
    return get(key, null, Boolean.class);
  }

  public final Boolean getBoolean(String key, String... aliases) {
    return get(key, null, Boolean.class, aliases);
  }

  public final boolean getBoolean(String key, boolean defaultValue, String... aliases) {
    return get(key, defaultValue, Boolean.class, aliases);
  }

  public final Integer getInteger(String key) {
    return get(key, null, Integer.class);
  }

  public final Integer getInteger(String key, String... aliases) {
    return get(key, null, Integer.class, aliases);
  }

  public final int getInteger(String key, int defaultValue, String... aliases) {
    return get(key, defaultValue, Integer.class, aliases);
  }

  public final Long getLong(String key) {
    return get(key, null, Long.class);
  }

  public final Long getLong(String key, String... aliases) {
    return get(key, null, Long.class, aliases);
  }

  public final long getLong(String key, long defaultValue, String... aliases) {
    return get(key, defaultValue, Long.class, aliases);
  }

  public final Float getFloat(String key, String... aliases) {
    return get(key, null, Float.class, aliases);
  }

  public final float getFloat(String key, float defaultValue) {
    return get(key, defaultValue, Float.class);
  }

  public final Double getDouble(String key) {
    return get(key, null, Double.class);
  }

  public final double getDouble(String key, double defaultValue) {
    return get(key, defaultValue, Double.class);
  }

  private <T> T get(String key, T defaultValue, Class<T> type, String... aliases) {
    for (ConfigProvider.Source source : sources) {
      T value;
      try {
        value = ConfigConverter.valueOf(source.get(key, aliases), type);
      } catch (NumberFormatException ex) {
        continue;
      }
      if (value != null) {
        return value;
      }
    }
    return defaultValue;
  }

  public final List<String> getList(String key) {
    return ConfigConverter.parseList(getString(key));
  }

  public final List<String> getSpacedList(String key) {
    return ConfigConverter.parseList(getString(key), " ");
  }

  public final Map<String, String> getMergedMap(String key) {
    Map<String, String> merged = new HashMap<>();
    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key);
      merged.putAll(ConfigConverter.parseMap(value, key));
    }
    return merged;
  }

  public final Map<String, String> getOrderedMap(String key) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key);
      map.putAll(ConfigConverter.parseOrderedMap(value, key));
    }
    return map;
  }

  public final Map<String, String> getMergedMapWithOptionalMappings(
      String defaultPrefix, boolean lowercaseKeys, String... keys) {
    Map<String, String> merged = new HashMap<>();
    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    for (String key : keys) {
      for (int i = sources.length - 1; 0 <= i; i--) {
        String value = sources[i].get(key);
        merged.putAll(
            ConfigConverter.parseMapWithOptionalMappings(value, key, defaultPrefix, lowercaseKeys));
      }
    }
    return merged;
  }

  public BitSet getIntegerRange(final String key, final BitSet defaultValue) {
    final String value = getString(key);
    try {
      return value == null ? defaultValue : ConfigConverter.parseIntegerRangeSet(value, key);
    } catch (final NumberFormatException e) {
      log.warn("Invalid configuration for " + key, e);
      return defaultValue;
    }
  }

  public static ConfigProvider getInstance() {
    return Singleton.INSTANCE;
  }

  public static ConfigProvider createDefault() {
    Properties configProperties =
        loadConfigurationFile(
            new ConfigProvider(new SystemPropertiesConfigSource(), new EnvironmentConfigSource()));
    if (configProperties.isEmpty()) {
      return new ConfigProvider(
          new SystemPropertiesConfigSource(),
          new EnvironmentConfigSource(),
          new CapturedEnvironmentConfigSource());
    } else {
      return new ConfigProvider(
          new SystemPropertiesConfigSource(),
          new EnvironmentConfigSource(),
          new PropertiesConfigSource(configProperties, true),
          new CapturedEnvironmentConfigSource());
    }
  }

  public static ConfigProvider withPropertiesOverride(Properties properties) {
    PropertiesConfigSource providedConfigSource = new PropertiesConfigSource(properties, false);
    Properties configProperties =
        loadConfigurationFile(
            new ConfigProvider(
                new SystemPropertiesConfigSource(),
                new EnvironmentConfigSource(),
                providedConfigSource));
    if (configProperties.isEmpty()) {
      return new ConfigProvider(
          new SystemPropertiesConfigSource(),
          new EnvironmentConfigSource(),
          providedConfigSource,
          new CapturedEnvironmentConfigSource());
    } else {
      return new ConfigProvider(
          providedConfigSource,
          new SystemPropertiesConfigSource(),
          new EnvironmentConfigSource(),
          new PropertiesConfigSource(configProperties, true),
          new CapturedEnvironmentConfigSource());
    }
  }

  /**
   * Loads the optional configuration properties file into the global {@link Properties} object.
   *
   * @return The {@link Properties} object. the returned instance might be empty of file does not
   *     exist or if it is in a wrong format.
   * @param configProvider
   */
  @SuppressForbidden
  private static Properties loadConfigurationFile(ConfigProvider configProvider) {
    final Properties properties = new Properties();

    // Reading from system property first and from env after
    String configurationFilePath = configProvider.getString(CONFIGURATION_FILE);
    if (null == configurationFilePath) {
      return properties;
    }

    // Normalizing tilde (~) paths for unix systems
    configurationFilePath =
        configurationFilePath.replaceFirst("^~", System.getProperty("user.home"));

    // Configuration properties file is optional
    final File configurationFile = new File(configurationFilePath);
    if (!configurationFile.exists()) {
      log.error("Configuration file '{}' not found.", configurationFilePath);
      return properties;
    }

    try (final FileReader fileReader = new FileReader(configurationFile)) {
      properties.load(fileReader);
    } catch (final FileNotFoundException fnf) {
      log.error("Configuration file '{}' not found.", configurationFilePath);
    } catch (final IOException ioe) {
      log.error(
          "Configuration file '{}' cannot be accessed or correctly parsed.", configurationFilePath);
    }

    properties.setProperty(PropertiesConfigSource.CONFIG_FILE_STATUS, configurationFilePath);

    return properties;
  }

  public abstract static class Source {
    public final String get(String key, String... aliases) {
      String value = get(key);
      if (value != null) {
        return value;
      }
      for (String alias : aliases) {
        value = get(alias);
        if (value != null) {
          return value;
        }
      }
      return null;
    }

    protected abstract String get(String key);

    protected final void collect(String key, String value) {
      if (key != null && value != null) {
        ConfigCollector.get().put(key, value);
      }
    }
  }
}

package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.config.GeneralConfig.CONFIGURATION_FILE;

import datadog.environment.SystemProperties;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigProvider {
  private static final class Singleton {
    private static final ConfigProvider INSTANCE = ConfigProvider.createDefault();
  }

  private static final Logger log = LoggerFactory.getLogger(ConfigProvider.class);

  private final boolean collectConfig;

  private final ConfigProvider.Source[] sources;
  private final int numSources;

  private ConfigProvider(ConfigProvider.Source... sources) {
    this(true, sources);
  }

  private ConfigProvider(boolean collectConfig, ConfigProvider.Source... sources) {
    this.collectConfig = collectConfig;
    this.sources = sources;
    this.numSources = sources.length;
  }

  public String getConfigFileStatus() {
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

  public String getString(String key) {
    return getString(key, null);
  }

  public <T extends Enum<T>> T getEnum(String key, Class<T> enumType, T defaultValue) {
    String value = getString(key);
    if (null != value) {
      try {
        return Enum.valueOf(enumType, value);
      } catch (Exception ignoreAndUseDefault) {
        log.debug("failed to parse {} for {}, defaulting to {}", value, key, defaultValue);
      }
    }
    if (collectConfig) {
      String valueStr = defaultValue == null ? null : defaultValue.name();
      ConfigCollector.get().put(key, valueStr, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return defaultValue;
  }

  public String getString(String key, String defaultValue, String... aliases) {
    String foundValue = null;
    for (int i = 0; i < sources.length; i++) {
      ConfigProvider.Source source = sources[i];
      String value = source.get(key, aliases);
      if (value != null) {
        if (collectConfig) {
          int seqId = sources.length - i + 1;
          ConfigCollector.get().put(key, value, source.origin(), seqId);
        }
        if (foundValue == null) {
          foundValue = value;
        }
      }
    }
    if (collectConfig) {
      ConfigCollector.get()
          .put(key, defaultValue, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return foundValue != null ? foundValue : defaultValue;
  }

  /**
   * Like {@link #getString(String, String, String...)} but falls back to next source if a value is
   * an empty or blank string.
   */
  public String getStringNotEmpty(String key, String defaultValue, String... aliases) {
    String foundValue = null;
    for (int i = 0; i < sources.length; i++) {
      ConfigProvider.Source source = sources[i];
      String value = source.get(key, aliases);
      if (value != null && !value.trim().isEmpty()) {
        if (collectConfig) {
          int seqId = sources.length - i + 1;
          ConfigCollector.get().put(key, value, source.origin(), seqId);
        }
        if (foundValue == null) {
          foundValue = value;
        }
      }
    }
    if (collectConfig) {
      ConfigCollector.get()
          .put(key, defaultValue, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return foundValue != null ? foundValue : defaultValue;
  }

  public String getStringExcludingSource(
      String key,
      String defaultValue,
      Class<? extends ConfigProvider.Source> excludedSource,
      String... aliases) {
    String foundValue = null;
    for (int i = 0; i < sources.length; i++) {
      ConfigProvider.Source source = sources[i];
      // Do we still want to report telemetry in this case?
      if (excludedSource.isAssignableFrom(source.getClass())) {
        continue;
      }
      String value = source.get(key, aliases);
      if (value != null) {
        if (collectConfig) {
          int seqId = sources.length - i + 1;
          ConfigCollector.get().put(key, value, source.origin(), seqId);
        }
        if (foundValue == null) {
          foundValue = value;
        }
      }
    }
    if (collectConfig) {
      ConfigCollector.get()
          .put(key, defaultValue, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return foundValue != null ? foundValue : defaultValue;
  }

  public boolean isSet(String key) {
    String value = getString(key);
    return value != null && !value.isEmpty();
  }

  public Boolean getBoolean(String key) {
    return get(key, null, Boolean.class);
  }

  public Boolean getBoolean(String key, String... aliases) {
    return get(key, null, Boolean.class, aliases);
  }

  public boolean getBoolean(String key, boolean defaultValue, String... aliases) {
    return get(key, defaultValue, Boolean.class, aliases);
  }

  public Integer getInteger(String key) {
    return get(key, null, Integer.class);
  }

  public Integer getInteger(String key, String... aliases) {
    return get(key, null, Integer.class, aliases);
  }

  public int getInteger(String key, int defaultValue, String... aliases) {
    return get(key, defaultValue, Integer.class, aliases);
  }

  public Long getLong(String key) {
    return get(key, null, Long.class);
  }

  public Long getLong(String key, String... aliases) {
    return get(key, null, Long.class, aliases);
  }

  public long getLong(String key, long defaultValue, String... aliases) {
    return get(key, defaultValue, Long.class, aliases);
  }

  public Float getFloat(String key, String... aliases) {
    return get(key, null, Float.class, aliases);
  }

  public float getFloat(String key, float defaultValue) {
    return get(key, defaultValue, Float.class);
  }

  public Double getDouble(String key) {
    return get(key, null, Double.class);
  }

  public double getDouble(String key, double defaultValue) {
    return get(key, defaultValue, Double.class);
  }

  private <T> T get(String key, T defaultValue, Class<T> type, String... aliases) {
    T foundValue = null;
    for (int i = 0; i < sources.length; i++) {
      ConfigProvider.Source source = sources[i];
      try {
        String sourceValue = source.get(key, aliases);
        T value = ConfigConverter.valueOf(sourceValue, type);
        if (value != null) {
          if (collectConfig) {
            int seqId = sources.length - i + 1;
            ConfigCollector.get().put(key, sourceValue, source.origin(), seqId);
          }
          if (foundValue == null) {
            foundValue = value;
          }
        }
      } catch (NumberFormatException ex) {
        // continue
      }
    }
    if (collectConfig) {
      ConfigCollector.get()
          .put(key, defaultValue, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return foundValue != null ? foundValue : defaultValue;
  }

  public List<String> getList(String key) {
    return ConfigConverter.parseList(getString(key));
  }

  public List<String> getList(String key, List<String> defaultValue) {
    String list = getString(key);
    if (null == list) {
      if (collectConfig) {
        ConfigCollector.get()
            .put(key, defaultValue, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
      }
      return defaultValue;
    } else {
      return ConfigConverter.parseList(list);
    }
  }

  public Set<String> getSet(String key, Set<String> defaultValue) {
    String list = getString(key);
    if (null == list) {
      if (collectConfig) {
        ConfigCollector.get()
            .put(key, defaultValue, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
      }
      return defaultValue;
    } else {
      return new HashSet(ConfigConverter.parseList(list));
    }
  }

  public List<String> getSpacedList(String key) {
    return ConfigConverter.parseList(getString(key), " ");
  }

  public Map<String, String> getMergedMap(String key, String... aliases) {
    Map<String, String> merged = new HashMap<>();
    ConfigOrigin origin = ConfigOrigin.DEFAULT;
    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    int seqId = 2;
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key, aliases);
      Map<String, String> parsedMap = ConfigConverter.parseMap(value, key);
      if (!parsedMap.isEmpty()) {
        origin = sources[i].origin();
        if (collectConfig) {
          seqId++;
          ConfigCollector.get().put(key, parsedMap, origin, seqId);
        }
      }
      merged.putAll(parsedMap);
    }
    // TODO: How to report telemetry about the final, mergedMap value? What is its correct origin?
    if (collectConfig && merged.isEmpty()) {
      ConfigCollector.get().put(key, merged, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return merged;
  }

  public Map<String, String> getMergedTagsMap(String key, String... aliases) {
    Map<String, String> merged = new HashMap<>();
    ConfigOrigin origin = ConfigOrigin.DEFAULT;
    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    int seqId = 2;
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key, aliases);
      Map<String, String> parsedMap =
          ConfigConverter.parseTraceTagsMap(value, ':', Arrays.asList(',', ' '));
      if (!parsedMap.isEmpty()) {
        origin = sources[i].origin();
        if (collectConfig) {
          seqId++;
          ConfigCollector.get().put(key, parsedMap, origin, seqId);
        }
      }
      merged.putAll(parsedMap);
    }
    if (collectConfig && merged.isEmpty()) {
      ConfigCollector.get().put(key, merged, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return merged;
  }

  public Map<String, String> getOrderedMap(String key) {
    LinkedHashMap<String, String> merged = new LinkedHashMap<>();
    ConfigOrigin origin = ConfigOrigin.DEFAULT;
    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    int seqId = 2;
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key);
      Map<String, String> parsedMap = ConfigConverter.parseOrderedMap(value, key);
      if (!parsedMap.isEmpty()) {
        origin = sources[i].origin();
        if (collectConfig) {
          seqId++;
          ConfigCollector.get().put(key, parsedMap, origin, seqId);
        }
      }
      merged.putAll(parsedMap);
    }
    if (collectConfig && merged.isEmpty()) {
      ConfigCollector.get().put(key, merged, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return merged;
  }

  public Map<String, String> getMergedMapWithOptionalMappings(
      String defaultPrefix, boolean lowercaseKeys, String... keys) {
    Map<String, String> merged = new HashMap<>();
    ConfigOrigin origin = ConfigOrigin.DEFAULT;
    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    int seqId = 2;
    for (String key : keys) {
      for (int i = sources.length - 1; 0 <= i; i--) {
        String value = sources[i].get(key);
        Map<String, String> parsedMap =
            ConfigConverter.parseMapWithOptionalMappings(value, key, defaultPrefix, lowercaseKeys);
        if (!parsedMap.isEmpty()) {
          origin = sources[i].origin();
          if (collectConfig) {
            seqId++;
            ConfigCollector.get().put(key, parsedMap, origin, seqId);
          }
        }
        merged.putAll(parsedMap);
      }
      if (collectConfig && merged.isEmpty()) {
        ConfigCollector.get()
            .put(key, Collections.emptyMap(), ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
      }
    }
    return merged;
  }

  public BitSet getIntegerRange(final String key, final BitSet defaultValue, String... aliases) {
    final String value = getString(key, null, aliases);
    try {
      if (value != null) {
        return ConfigConverter.parseIntegerRangeSet(value, key);
      }
    } catch (final NumberFormatException e) {
      log.warn("Invalid configuration for {}", key, e);
    }
    if (collectConfig) {
      ConfigCollector.get()
          .put(key, defaultValue, ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    }
    return defaultValue;
  }

  public boolean isEnabled(
      final Iterable<String> settingNames,
      final String settingPrefix,
      final String settingSuffix,
      final boolean defaultEnabled) {
    // If default is enabled, we want to disable individually.
    // If default is disabled, we want to enable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : settingNames) {
      final String configKey = settingPrefix + name + settingSuffix;
      final String fullKey = configKey.startsWith("trace.") ? configKey : "trace." + configKey;
      final boolean configEnabled = getBoolean(fullKey, defaultEnabled, configKey);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
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
          StableConfigSource.FLEET,
          new EnvironmentConfigSource(),
          new OtelEnvironmentConfigSource(),
          StableConfigSource.LOCAL,
          new CapturedEnvironmentConfigSource());
    } else {
      return new ConfigProvider(
          new SystemPropertiesConfigSource(),
          StableConfigSource.FLEET,
          new EnvironmentConfigSource(),
          new PropertiesConfigSource(configProperties, true),
          new OtelEnvironmentConfigSource(configProperties),
          StableConfigSource.LOCAL,
          new CapturedEnvironmentConfigSource());
    }
  }

  public static ConfigProvider withoutCollector() {
    Properties configProperties =
        loadConfigurationFile(
            new ConfigProvider(
                false, new SystemPropertiesConfigSource(), new EnvironmentConfigSource()));
    if (configProperties.isEmpty()) {
      return new ConfigProvider(
          false,
          new SystemPropertiesConfigSource(),
          StableConfigSource.FLEET,
          new EnvironmentConfigSource(),
          new OtelEnvironmentConfigSource(),
          StableConfigSource.LOCAL,
          new CapturedEnvironmentConfigSource());
    } else {
      return new ConfigProvider(
          false,
          new SystemPropertiesConfigSource(),
          StableConfigSource.FLEET,
          new EnvironmentConfigSource(),
          new PropertiesConfigSource(configProperties, true),
          new OtelEnvironmentConfigSource(configProperties),
          StableConfigSource.LOCAL,
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
          StableConfigSource.FLEET,
          new EnvironmentConfigSource(),
          providedConfigSource,
          new OtelEnvironmentConfigSource(),
          StableConfigSource.LOCAL,
          new CapturedEnvironmentConfigSource());
    } else {
      return new ConfigProvider(
          providedConfigSource,
          new SystemPropertiesConfigSource(),
          StableConfigSource.FLEET,
          new EnvironmentConfigSource(),
          new PropertiesConfigSource(configProperties, true),
          new OtelEnvironmentConfigSource(configProperties),
          StableConfigSource.LOCAL,
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
    String home;
    if (configurationFilePath.charAt(0) == '~'
        && (home = SystemProperties.get("user.home")) != null) {
      configurationFilePath = home + configurationFilePath.substring(1);
    }

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

    public abstract ConfigOrigin origin();
  }
}

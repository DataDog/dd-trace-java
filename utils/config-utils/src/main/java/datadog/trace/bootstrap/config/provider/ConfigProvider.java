package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.ConfigSetting.ABSENT_SEQ_ID;
import static datadog.trace.api.ConfigSetting.NON_DEFAULT_SEQ_ID;
import static datadog.trace.api.config.GeneralConfig.CONFIGURATION_FILE;

import datadog.environment.SystemProperties;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.bootstrap.config.provider.civisibility.CiEnvironmentVariables;
import datadog.trace.util.ConfigStrings;
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
import java.util.Objects;
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

  private ConfigProvider(ConfigProvider.Source... sources) {
    this(true, sources);
  }

  private ConfigProvider(boolean collectConfig, ConfigProvider.Source... sources) {
    this.collectConfig = collectConfig;
    this.sources = sources;
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

  // Gets a string value when there is no default value.
  public String getString(String key) {
    return getString(key, null);
  }

  /**
   * Gets a string value with a default fallback and optional aliases. Use for configs with
   * meaningful defaults. Reports default to telemetry.
   */
  public String getString(String key, String defaultValue, String... aliases) {
    if (collectConfig) {
      reportDefault(key, defaultValue);
    }
    String value = getStringInternal(key, aliases);

    return value != null ? value : defaultValue;
  }

  // Internal helper that performs configuration source lookup and reports values from non-default
  // sources to telemetry.
  private String getStringInternal(String key, String... aliases) {
    ConfigValueResolver<String> resolver = null;
    int seqId = NON_DEFAULT_SEQ_ID;

    for (int i = sources.length - 1; i >= 0; i--) {
      ConfigProvider.Source source = sources[i];
      String candidate = source.get(key, aliases);
      // Create resolver if we have a valid candidate
      if (candidate != null) {
        resolver = ConfigValueResolver.of(candidate);
        // And report to telemetry
        if (collectConfig) {
          ConfigCollector.get()
              .put(key, candidate, source.origin(), seqId, getConfigIdFromSource(source));
        }
      }

      seqId++;
    }

    return resolver != null ? resolver.value : null;
  }

  public <T extends Enum<T>> T getEnum(String key, Class<T> enumType, T defaultValue) {
    return getEnum(key, enumType, defaultValue, true);
  }

  public <T extends Enum<T>> T getEnum(
      String key, Class<T> enumType, T defaultValue, boolean isValueCaseSensitive) {
    return getEnum(key, enumType, defaultValue, isValueCaseSensitive, "", "");
  }

  public <T extends Enum<T>> T getEnum(
      String key,
      Class<T> enumType,
      T defaultValue,
      boolean isValueCaseSensitive,
      String charToReplaceInRawValue,
      String newCharInValue) {
    if (collectConfig) {
      String defaultValueString = defaultValue == null ? null : defaultValue.name();
      reportDefault(key, defaultValueString);
    }
    String value = getStringInternal(key);
    if (null != value) {
      try {
        return Enum.valueOf(
            enumType,
            isValueCaseSensitive
                ? value.replace(charToReplaceInRawValue, newCharInValue)
                : value.toUpperCase().replace(charToReplaceInRawValue, newCharInValue));
      } catch (Exception ignoreAndUseDefault) {
        log.debug("failed to parse {} for {}, defaulting to {}", value, key, defaultValue);
      }
    }
    return defaultValue;
  }

  /**
   * Like {@link #getString(String, String, String...)} but falls back to next source if a value is
   * an empty or blank string.
   */
  public String getStringNotEmpty(String key, String defaultValue, String... aliases) {
    if (collectConfig) {
      reportDefault(key, defaultValue);
    }

    ConfigValueResolver<String> resolver = null;
    int seqId = NON_DEFAULT_SEQ_ID;

    for (int i = sources.length - 1; i >= 0; i--) {
      ConfigProvider.Source source = sources[i];
      String candidateValue = source.get(key, aliases);

      // Report any non-null values to telemetry
      if (candidateValue != null) {
        if (collectConfig) {
          ConfigCollector.get()
              .put(key, candidateValue, source.origin(), seqId, getConfigIdFromSource(source));
        }
        // Create resolver only if candidate is not empty or blank
        if (!candidateValue.trim().isEmpty()) {
          resolver =
              ConfigValueResolver.of(
                  candidateValue, source.origin(), seqId, getConfigIdFromSource(source));
        }
      }

      seqId++;
    }

    // Re-report the chosen value with the highest seqId
    if (resolver != null && collectConfig) {
      resolver.reReportToCollector(key, seqId + 1);
    }

    return resolver != null ? resolver.value : defaultValue;
  }

  public String getStringExcludingSource(
      String key,
      String defaultValue,
      Class<? extends ConfigProvider.Source> excludedSource,
      String... aliases) {
    if (collectConfig) {
      reportDefault(key, defaultValue);
    }
    ConfigValueResolver<String> resolver = null;
    int seqId = NON_DEFAULT_SEQ_ID;
    for (int i = sources.length - 1; i >= 0; i--) {
      ConfigProvider.Source source = sources[i];
      String candidate = source.get(key, aliases);

      // Skip excluded source types
      if (excludedSource.isAssignableFrom(source.getClass())) {
        seqId++;
        continue;
      }
      if (candidate != null) {
        resolver = ConfigValueResolver.of(candidate);
        if (collectConfig) {
          ConfigCollector.get()
              .put(key, candidate, source.origin(), seqId, getConfigIdFromSource(source));
        }
      }
      seqId++;
    }
    return resolver != null ? resolver.value : defaultValue;
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
    if (collectConfig) {
      reportDefault(key, defaultValue);
    }

    ConfigValueResolver<T> resolver = null;
    int seqId = NON_DEFAULT_SEQ_ID;

    for (int i = sources.length - 1; i >= 0; i--) {
      String sourceValue = sources[i].get(key, aliases);
      String configId = getConfigIdFromSource(sources[i]);

      // Always report raw value to telemetry
      if (sourceValue != null && collectConfig) {
        ConfigCollector.get().put(key, sourceValue, sources[i].origin(), seqId, configId);
      }

      try {
        T candidate = ConfigConverter.valueOf(sourceValue, type);
        if (candidate != null) {
          resolver = ConfigValueResolver.of(candidate, sources[i].origin(), seqId, configId);
        }
      } catch (ConfigConverter.InvalidBooleanValueException ex) {
        // For backward compatibility: invalid boolean values should return false, not default
        // Store the invalid sourceValue for telemetry, but return false
        if (Boolean.class.equals(type)) {
          resolver =
              ConfigValueResolver.of((T) Boolean.FALSE, ConfigOrigin.CALCULATED, seqId, configId);
        }
        // For non-boolean types, continue to next source
      } catch (IllegalArgumentException ex) {
        // continue - covers both NumberFormatException and other IllegalArgumentException
      }

      seqId++;
    }

    // Re-report the chosen value and origin to ensure its seqId is higher than any error configs
    if (resolver != null && collectConfig) {
      resolver.reReportToCollector(key, seqId + 1);
    }

    return resolver != null ? resolver.value : defaultValue;
  }

  public List<String> getList(String key) {
    return ConfigConverter.parseList(getString(key));
  }

  public List<String> getList(String key, List<String> defaultValue) {
    // Ensure the first item at DEFAULT is the accurate one
    if (collectConfig) {
      reportDefault(key, defaultValue);
    }
    String list = getStringInternal(key);
    if (null == list) {
      return defaultValue;
    } else {
      return ConfigConverter.parseList(list);
    }
  }

  public Set<String> getSet(String key, Set<String> defaultValue) {
    // Ensure the first item at DEFAULT is the most accurate one
    if (collectConfig) {
      reportDefault(key, defaultValue);
    }
    String list = getStringInternal(key);
    if (null == list) {
      return defaultValue;
    } else {
      return new HashSet(ConfigConverter.parseList(list));
    }
  }

  public List<String> getSpacedList(String key) {
    return ConfigConverter.parseList(getString(key), " ");
  }

  public Map<String, String> getMergedMap(String key, String... aliases) {
    return getMergedMap(key, ':', aliases);
  }

  public Map<String, String> getMergedMap(String key, char keyValueDelimiter, String... aliases) {
    ConfigMergeResolver mergeResolver = new ConfigMergeResolver(new HashMap<>());
    int seqId = NON_DEFAULT_SEQ_ID;

    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key, aliases);
      Map<String, String> parsedMap = ConfigConverter.parseMap(value, key, keyValueDelimiter);

      if (!parsedMap.isEmpty()) {
        if (collectConfig) {
          seqId++;
          ConfigCollector.get()
              .put(key, parsedMap, sources[i].origin(), seqId, getConfigIdFromSource(sources[i]));
        }
        mergeResolver.addContribution(parsedMap, sources[i].origin());
      }
    }

    if (collectConfig) {
      reportDefault(key, Collections.emptyMap());
      mergeResolver.reReportFinalResult(key, seqId);
    }

    return mergeResolver.getMergedValue();
  }

  public Map<String, String> getMergedTagsMap(String key, String... aliases) {
    ConfigMergeResolver mergeResolver = new ConfigMergeResolver(new HashMap<>());
    int seqId = NON_DEFAULT_SEQ_ID;

    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key, aliases);
      Map<String, String> parsedMap =
          ConfigConverter.parseTraceTagsMap(value, ':', Arrays.asList(',', ' '));

      if (!parsedMap.isEmpty()) {
        if (collectConfig) {
          seqId++;
          ConfigCollector.get()
              .put(key, parsedMap, sources[i].origin(), seqId, getConfigIdFromSource(sources[i]));
        }
        mergeResolver.addContribution(parsedMap, sources[i].origin());
      }
    }

    if (collectConfig) {
      reportDefault(key, Collections.emptyMap());
      mergeResolver.reReportFinalResult(key, seqId);
    }

    return mergeResolver.getMergedValue();
  }

  public Map<String, String> getOrderedMap(String key) {
    // Use LinkedHashMap to preserve insertion order of map entries
    ConfigMergeResolver mergeResolver = new ConfigMergeResolver(new LinkedHashMap<>());
    int seqId = NON_DEFAULT_SEQ_ID;

    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    for (int i = sources.length - 1; 0 <= i; i--) {
      String value = sources[i].get(key);
      Map<String, String> parsedMap = ConfigConverter.parseOrderedMap(value, key);

      if (!parsedMap.isEmpty()) {
        if (collectConfig) {
          seqId++;
          ConfigCollector.get()
              .put(key, parsedMap, sources[i].origin(), seqId, getConfigIdFromSource(sources[i]));
        }
        mergeResolver.addContribution(parsedMap, sources[i].origin());
      }
    }

    if (collectConfig) {
      reportDefault(key, Collections.emptyMap());
      mergeResolver.reReportFinalResult(key, seqId);
    }

    return mergeResolver.getMergedValue();
  }

  public Map<String, String> getMergedMapWithOptionalMappings(
      String defaultPrefix, boolean lowercaseKeys, String... keys) {
    ConfigMergeResolver mergeResolver = new ConfigMergeResolver(new HashMap<>());
    int seqId = NON_DEFAULT_SEQ_ID;

    // System properties take precedence over env
    // prior art:
    // https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html
    // We reverse iterate to allow overrides
    for (String key : keys) {
      for (int i = sources.length - 1; 0 <= i; i--) {
        String value = sources[i].get(key);
        Map<String, String> parsedMap =
            ConfigConverter.parseMapWithOptionalMappings(value, key, defaultPrefix, lowercaseKeys);

        if (!parsedMap.isEmpty()) {
          if (collectConfig) {
            seqId++;
            ConfigCollector.get()
                .put(key, parsedMap, sources[i].origin(), seqId, getConfigIdFromSource(sources[i]));
          }
          mergeResolver.addContribution(parsedMap, sources[i].origin());
        }
      }

      if (collectConfig) {
        reportDefault(key, Collections.emptyMap());
        mergeResolver.reReportFinalResult(key, seqId);
      }
    }

    return mergeResolver.getMergedValue();
  }

  public BitSet getIntegerRange(final String key, final BitSet defaultValue, String... aliases) {
    // Ensure the first item at DEFAULT is the most accurate one
    if (collectConfig) {
      reportDefault(key, defaultValue);
    }
    final String value = getStringInternal(key, aliases);
    try {
      if (value != null) {
        return ConfigConverter.parseIntegerRangeSet(value, key);
      }
    } catch (final NumberFormatException e) {
      log.warn("Invalid configuration for {}", key, e);
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
    ConfigProvider.Source propertiesSource =
        !configProperties.isEmpty() ? new PropertiesConfigSource(configProperties, true) : null;

    Map<String, String> ciEnvironmentVariables = CiEnvironmentVariables.getAll();
    ConfigProvider.Source ciEnvironmentSource =
        ciEnvironmentVariables != null
            ? new MapConfigSource(
                ciEnvironmentVariables,
                ConfigStrings::propertyNameToEnvironmentVariableName,
                ConfigOrigin.ENV)
            : null;

    return new ConfigProvider(
        filterNonNull(
            new SystemPropertiesConfigSource(),
            StableConfigSource.FLEET,
            ciEnvironmentSource,
            new EnvironmentConfigSource(),
            propertiesSource,
            new OtelEnvironmentConfigSource(),
            StableConfigSource.LOCAL,
            new CapturedEnvironmentConfigSource()));
  }

  private static ConfigProvider.Source[] filterNonNull(ConfigProvider.Source... values) {
    return Arrays.stream(values).filter(Objects::nonNull).toArray(Source[]::new);
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

  private static String getConfigIdFromSource(Source source) {
    if (source instanceof StableConfigSource) {
      return ((StableConfigSource) source).getConfigId();
    }
    return null;
  }

  private static <T> void reportDefault(String key, T defaultValue) {
    ConfigCollector.get().putDefault(key, defaultValue);
  }

  /** Helper class to store resolved configuration values with their metadata */
  static final class ConfigValueResolver<T> {
    final T value;
    final ConfigOrigin origin;
    final int seqId;
    final String configId;

    // Single constructor that takes all fields
    private ConfigValueResolver(T value, ConfigOrigin origin, int seqId, String configId) {
      this.value = value;
      this.origin = origin;
      this.seqId = seqId;
      this.configId = configId;
    }

    // Factory method for cases where we only care about the value (e.g., getString)
    static <T> ConfigValueResolver<T> of(T value) {
      return new ConfigValueResolver<>(value, null, ABSENT_SEQ_ID, null);
    }

    // Factory method for cases where we need to re-report (e.g., getStringNotEmpty, get<T>)
    static <T> ConfigValueResolver<T> of(T value, ConfigOrigin origin, int seqId, String configId) {
      return new ConfigValueResolver<>(value, origin, seqId, configId);
    }

    /** Re-reports this resolved value to ConfigCollector with the specified seqId */
    void reReportToCollector(String key, int finalSeqId) {
      // Value should never be null if there is an initialized ConfigValueResolver
      if (origin != null) {
        ConfigCollector.get().put(key, value, origin, finalSeqId, configId);
      }
    }
  }

  /** Helper class for methods that merge map values from multiple sources (e.g., getMergedMap) */
  private static final class ConfigMergeResolver {
    private final Map<String, String> mergedValue;
    private ConfigOrigin currentOrigin;

    ConfigMergeResolver(Map<String, String> initialValue) {
      this.mergedValue = initialValue;
      this.currentOrigin = ConfigOrigin.DEFAULT;
    }

    /** Adds a contribution from a source and updates the origin tracking */
    void addContribution(Map<String, String> contribution, ConfigOrigin sourceOrigin) {
      mergedValue.putAll(contribution);

      // Update origin: DEFAULT -> source origin -> CALCULATED if multiple sources
      if (currentOrigin != ConfigOrigin.DEFAULT) {
        // if we already have a non-default origin, the value is calculated from multiple sources
        currentOrigin = ConfigOrigin.CALCULATED;
      } else {
        currentOrigin = sourceOrigin;
      }
    }

    /**
     * Re-reports the final merged result to ConfigCollector if it has actual contributions. Does
     * NOT re-report when no contributions were made since defaults are reported separately.
     */
    void reReportFinalResult(String key, int finalSeqId) {
      if (currentOrigin != ConfigOrigin.DEFAULT && !mergedValue.isEmpty()) {
        ConfigCollector.get().put(key, mergedValue, currentOrigin, finalSeqId);
      }
    }

    /** Gets the final merged value */
    Map<String, String> getMergedValue() {
      return mergedValue;
    }
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

package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.ConfigStrings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import datadog.trace.bootstrap.config.provider.stableconfig.StableConfigMappingException;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StableConfigSource extends ConfigProvider.Source {
  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);

  public static final String LOCAL_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/application_monitoring.yaml";
  public static final String FLEET_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/managed/datadog-agent/stable/application_monitoring.yaml";
  public static final StableConfigSource LOCAL =
      new StableConfigSource(LOCAL_STABLE_CONFIG_PATH, ConfigOrigin.LOCAL_STABLE_CONFIG);
  public static final StableConfigSource FLEET =
      new StableConfigSource(
          StableConfigSource.FLEET_STABLE_CONFIG_PATH, ConfigOrigin.FLEET_STABLE_CONFIG);

  private final ConfigOrigin fileOrigin;
  private final StableConfig config;

  StableConfigSource(String filePath, ConfigOrigin origin) {
    StableConfig cfg = StableConfig.EMPTY;
    this.fileOrigin = origin;
    try {
      File file = new File(filePath);
      log.debug("Stable configuration file found at path: {}", file);
      if (file.exists()) {
        cfg = StableConfigParser.parse(filePath);
      }
    } catch (Throwable e) {
      if (e instanceof StableConfigMappingException
          || e instanceof IllegalArgumentException
          || e instanceof ClassCastException
          || e instanceof NullPointerException) {
        log.warn(
            "YAML mapping error in stable configuration file: {}, error: {}",
            filePath,
            e.getMessage());
      } else if (log.isDebugEnabled()) {
        log.error("Unexpected error while reading stable configuration file: {}", filePath, e);
      } else {
        log.error(
            "Unexpected error while reading stable configuration file: {}, error: {}",
            filePath,
            e.getMessage());
      }
    }
    this.config = cfg;
  }

  @Override
  public String get(String key) {
    if (this.config == StableConfig.EMPTY) {
      return null;
    }
    return this.config.get(propertyNameToEnvironmentVariableName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return fileOrigin;
  }

  public Set<String> getKeys() {
    return this.config.getKeys();
  }

  public String getConfigId() {
    return this.config.getConfigId();
  }

  public static class StableConfig {
    public static final StableConfig EMPTY = new StableConfig(null, Collections.emptyMap());
    private final Map<String, Object> apmConfiguration;
    private final String configId;

    public StableConfig(String configId, Map<String, Object> configMap) {
      this.configId = configId;
      this.apmConfiguration = configMap;
    }

    public String get(String key) {
      Object value = this.apmConfiguration.get(key);
      return (value == null) ? null : String.valueOf(value);
    }

    public Set<String> getKeys() {
      return this.apmConfiguration.keySet();
    }

    public String getConfigId() {
      return this.configId;
    }
  }
}

package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
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
    this.fileOrigin = origin;
    File file = new File(filePath);
    if (!file.exists()) {
      this.config = StableConfig.EMPTY;
      return;
    }
    StableConfig cfg;
    try {
      log.debug("Stable configuration file found at path: {}", file);
      cfg = StableConfigParser.parse(filePath);
    } catch (Throwable e) {
      log.warn(
          "Encountered the following exception when attempting to read stable configuration file at path: {}, dropping configs.",
          file,
          e);
      cfg = StableConfig.EMPTY;
    }
    this.config = cfg;
  }

  public String get(String key) throws ConfigSourceException {
    if (this.config == StableConfig.EMPTY) {
      return null;
    }
    Object value = this.config.get(propertyNameToEnvironmentVariableName(key));
    if (value == null) {
      return null;
    }
    if (!(value instanceof String)) {
      throw new ConfigSourceException(value);
    }
    return (String) value;
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

    public Object get(String key) {
      return this.apmConfiguration.get(key);
    }

    public Set<String> getKeys() {
      return this.apmConfiguration.keySet();
    }

    public String getConfigId() {
      return this.configId;
    }
  }
}

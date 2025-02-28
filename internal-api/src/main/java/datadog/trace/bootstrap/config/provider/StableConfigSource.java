package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
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

  StableConfigSource(String file, ConfigOrigin origin) {
    this.fileOrigin = origin;
    StableConfig cfg;
    try {
      cfg = StableConfigParser.parse(file);
    } catch (Throwable e) {
      log.debug("Stable configuration file not readable at specified path: {}", file);
      cfg = StableConfig.EMPTY;
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
    private final Map<String, String> apmConfiguration;
    private final String configId;

    StableConfig(String configId, Map<String, String> configMap) {
      this.configId = configId;
      this.apmConfiguration = configMap;
    }

    public String get(String key) {
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

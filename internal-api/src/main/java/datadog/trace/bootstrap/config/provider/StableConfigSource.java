package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StableConfigSource extends ConfigProvider.Source {
  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);

  public static final String USER_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/application_monitoring.yaml";
  public static final String MANAGED_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/managed/datadog-agent/stable/application_monitoring.yaml";
  public static StableConfigSource USER =
      new StableConfigSource(USER_STABLE_CONFIG_PATH, ConfigOrigin.USER_STABLE_CONFIG);
  public static final StableConfigSource MANAGED =
      new StableConfigSource(
          StableConfigSource.MANAGED_STABLE_CONFIG_PATH, ConfigOrigin.MANAGED_STABLE_CONFIG);

  private final ConfigOrigin fileOrigin;

  private final StableConfig config;

  StableConfigSource(String file, ConfigOrigin origin) {
    this.fileOrigin = origin;
    StableConfig cfg;
    try {
      cfg = StableConfigParser.parse(file);
    } catch (Throwable e) {
      log.debug("Stable configuration file not available at specified path: {}", file);
      cfg = new StableConfig();
    }
    this.config = cfg;
  }

  @Override
  public String get(String key) {
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

  /*public?*/ static class StableConfig {
    private final Map<String, String> apmConfiguration;
    private String configId;

    StableConfig() {
      this.apmConfiguration = new HashMap<>();
      this.configId = null;
    }

    void put(String key, String value) {
      this.apmConfiguration.put(key, value);
    }

    void setConfigId(String configId) {
      this.configId = configId;
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

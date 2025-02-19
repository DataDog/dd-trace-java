package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class StableConfigSource extends ConfigProvider.Source {
  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);

  // Static to only read the files once
  private static FfiStableConfig.StableConfigResult ffiConfig;
  private static boolean ffiConfigLoaded = false;


  public static final String LOCAL_STABLE_CONFIG_PATH_OVERRIDE = ""; // Used for tests only
  public static final String FLEET_STABLE_CONFIG_PATH_OVERRIDE = ""; // Used for tests only
  
  public static StableConfigSource USER =
      new StableConfigSource(ConfigOrigin.USER_STABLE_CONFIG);
  public static final StableConfigSource MANAGED =
      new StableConfigSource(ConfigOrigin.MANAGED_STABLE_CONFIG);

  private final ConfigOrigin fileOrigin;

  private final StableConfig config;

  StableConfigSource(ConfigOrigin origin) {
    this.config = new StableConfig();
    this.fileOrigin = origin;

    if (!ffiConfigLoaded) {
      // Only do the IO once
      try (FfiStableConfig configurator = new FfiStableConfig(false);) {
        // Overrides for tests
        if (!LOCAL_STABLE_CONFIG_PATH_OVERRIDE.isEmpty()) {
          configurator.setLocalPath(LOCAL_STABLE_CONFIG_PATH_OVERRIDE);
        }
        if (!FLEET_STABLE_CONFIG_PATH_OVERRIDE.isEmpty()) {
          configurator.setFleetPath(FLEET_STABLE_CONFIG_PATH_OVERRIDE);
        }
        ffiConfig = configurator.getConfiguration();
        ffiConfigLoaded = true;
      } catch (Throwable t) {
        // Don't crash the customer app!
        log.warn("Failed to load configuration from libdatadog (is the library loaded?). Err: " + t.getMessage());
        return;
      }
    }
    
    Map<String, String> cfgMap;
    if (origin == ConfigOrigin.MANAGED_STABLE_CONFIG) {
      cfgMap = ffiConfig.fleet_configuration;
    } else {
      cfgMap = ffiConfig.local_configuration;
    }

    this.config.setConfigId(ffiConfig.config_id);
    for (Map.Entry<String, String> entry : cfgMap.entrySet()) {
      this.config.put(entry.getKey(), entry.getValue());
    }
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

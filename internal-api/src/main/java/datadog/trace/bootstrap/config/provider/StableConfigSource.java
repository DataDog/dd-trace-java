package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class StableConfigSource extends ConfigProvider.Source {
  static final String USER_STABLE_CONFIG_PATH = "/etc/datadog-agent/application_monitoring.yaml";
  static final String MANAGED_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/managed/datadog-apm-libraries/stable/application_monitoring.yaml ";
  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);

  final ConfigOrigin fileOrigin;
  HashMap<String, Object> configuration;

  StableConfigSource(String file, ConfigOrigin origin) {
    this.fileOrigin = origin;
    try {
      configuration = parseStableConfig(file);
    } catch (Exception e) {
      configuration = new HashMap<>();
    }
  }

  private static HashMap<String, Object> parseStableConfig(String filePath) throws IOException {
    HashMap<String, Object> config = new HashMap<>();

    // Check if the file exists
    File file = new File(filePath);
    if (!file.exists()) {
      log.error("Stable configuration file does not exist at the specified path: {}", filePath);
      return config; // Exit early or take other action as necessary
    }

    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    InputStream input = Files.newInputStream(new File(filePath).toPath());
    HashMap<String, Object> data = yaml.load(input);

    Object apmConfig = data.get("apm_configuration_default");
    if (apmConfig instanceof HashMap<?, ?>) {
      HashMap<?, ?> tempConfig = (HashMap<?, ?>) apmConfig;
      for (Map.Entry<?, ?> entry : tempConfig.entrySet()) {
        if (entry.getKey() instanceof String && entry.getValue() != null) {
          String key = String.valueOf(entry.getKey());
          Object value = entry.getValue();
          config.put(key, value);
        } else {
          log.debug("Configuration {} in unexpected format", entry.getKey());
        }
      }
    } else {
      // do something
      log.debug("File {} in unexpected format", filePath);
    }
    return config;
  };

  @Override
  public String get(String key) {
    return (String) configuration.get(propertyNameToEnvironmentVariableName(key));
  }

  @Override
  public ConfigOrigin origin() {
    return fileOrigin;
  }

  public Set<String> getKeys() {
    return this.configuration.keySet();
  }

  private static class StableConfig {
    private String config_id;
    private HashMap<String, Object> apm_configuration_default;

    private void setApmConfigurationDefault(HashMap<String, Object> m) {
      apm_configuration_default = m;
    }

    private void setConfigId(String i) {
      config_id = i;
    }
  }
}

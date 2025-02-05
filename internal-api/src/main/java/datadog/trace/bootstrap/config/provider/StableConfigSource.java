package datadog.trace.bootstrap.config.provider;

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
  Map<String, Object> configuration;
  String configId;

  StableConfigSource(String file, ConfigOrigin origin) throws IOException {
    this.fileOrigin = origin;
    HashMap<String, Object> data = readFile(file);
    if (data == null) {
      this.configuration = new HashMap<>();
      this.configId = null;
    } else {
      this.configId = (String) data.get("config_id"); // could be ""
      this.configuration = parseStableConfig(data);
    }
  }

  private static HashMap<String, Object> readFile(String filePath) {
    // Check if the file exists
    File file = new File(filePath);
    if (!file.exists()) {
      log.debug(
          "Stable configuration file does not exist at the specified path: {}, ignoring", filePath);
      return new HashMap<>();
    }

    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    InputStream input;
    try {
      input = Files.newInputStream(new File(filePath).toPath());
    } catch (IOException e) {
      //      throw new RuntimeException(e); // Do we want to do this? Or fail more gracefully?
      log.error("Unable to read from stable config file {}, dropping input", filePath);
      return new HashMap<>();
    }
    try {
      return yaml.load(input);
    } catch (Exception e) {
      log.error("YAML parsing error in stable config file {}: {}", filePath, e.getMessage());
      return new HashMap<>();
    }
  }

  private static Map<String, Object> parseStableConfig(HashMap<String, Object> data) {
    HashMap<String, Object> config = new HashMap<>();
    Object apmConfig = data.get("apm_configuration_default");
    if (apmConfig == null) {
      return config;
    }
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
      log.debug("File in unexpected format");
    }
    return config;
  };

  @Override
  public String get(String key) {
    return (String) configuration.get(key);
  }

  @Override
  public ConfigOrigin origin() {
    return fileOrigin;
  }

  public Set<String> getKeys() {
    return this.configuration.keySet();
  }

  public String getConfigId() {
    return this.configId;
  }

  //  private static class StableConfig {
  //    private String config_id;
  //    private Map<String, Object> apm_configuration_default;
  //
  //    private void setApmConfigurationDefault(HashMap<String, Object> m) {
  //      apm_configuration_default = m;
  //    }
  //
  //    private void setConfigId(String i) {
  //      config_id = i;
  //    }
  //  }
}

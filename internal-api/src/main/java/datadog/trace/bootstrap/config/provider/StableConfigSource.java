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
  public static final String USER_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/application_monitoring.yaml";
  public static final String MANAGED_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/managed/datadog-apm-libraries/stable/application_monitoring.yaml ";

  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);

  private final StableConfig config;
  private final ConfigOrigin fileOrigin;

  StableConfigSource(String file, ConfigOrigin origin) {
    this.fileOrigin = origin;
    this.config = newStableConfigFromFile(file);
  }

  private static StableConfig newStableConfigFromFile(String file) {
    try {
      HashMap<String, Object> data = readYamlFromFile(file);
      return buildStableConfig(data);
    } catch (IOException e) {
      log.error("Error reading from file: {}", e.getMessage());
    } catch (Exception e) {
      log.error("Error processing file: {}", e.getMessage());
    }
    return new StableConfig();
  }

  /**
   * Reads a YAML file from the specified file path and returns its contents as a HashMap.
   *
   * @param filePath The path to the YAML file to be read. It must be a valid path to an existing
   *     file.
   * @return A HashMap<String, Object> containing the parsed data from the YAML file.
   * @throws IOException If the specified file does not exist or cannot be accessed.
   * @throws Exception If there is an error during YAML parsing, including invalid formatting or
   *     structure.
   */
  public static HashMap<String, Object> readYamlFromFile(String filePath) throws Exception {
    File file = new File(filePath);
    if (!file.exists()) {
      throw new IOException(
          "Stable configuration file does not exist at the specified path: " + filePath);
    }
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    InputStream input = Files.newInputStream(new File(filePath).toPath());
    try {
      return yaml.load(input);
    } catch (Exception e) {
      throw new Exception(
          "YAML parsing error in stable config file " + filePath + ": " + e.getMessage(), e);
    }
  }

  /**
   * Creates a StableConfig object based on the provided data map.
   *
   * <p>This method extracts the "config_id" and "apm_configuration_default" values from the input
   * data map. If "apm_configuration_default" is a valid HashMap, it processes the entries and
   * constructs a new configuration map.
   *
   * @param data A HashMap<String, Object> containing the configuration data. It should contain keys
   *     such as "config_id" and "apm_configuration_default".
   * @return A StableConfig object constructed with the "config_id" if available and, if applicable,
   *     the parsed configuration data.
   */
  private static StableConfig buildStableConfig(Map<String, Object> data) {
    if (data == null) {
      return new StableConfig();
    }

    String configId = (String) data.get("config_id");
    Object apmConfig = data.get("apm_configuration_default");

    HashMap<String, Object> config = new HashMap<>();
    if (apmConfig instanceof HashMap<?, ?>) {
      HashMap<?, ?> tempConfig = (HashMap<?, ?>) apmConfig;
      for (Map.Entry<?, ?> entry : tempConfig.entrySet()) {
        if (entry.getKey() instanceof String && entry.getValue() != null) {
          config.put(String.valueOf(entry.getKey()), entry.getValue());
        } else {
          log.debug("Config key {} in unexpected format", entry.getKey());
        }
      }
      return new StableConfig(configId, config);
    } else {
      // do something
      log.debug("File in unexpected format");
      return new StableConfig(configId);
    }
  };

  /**
   * Searches for a specific key in the provided map and returns its associated value if found.
   *
   * <p>This method is designed to quickly check if a specific key exists within the
   * "apm_configuration_default" section of the provided data map and returns its associated value
   * as a string if found.
   *
   * @param data A HashMap<String, Object> containing the configuration data. The key
   *     "apm_configuration_default" should be present, containing a nested map.
   * @param key The key to search for within the "apm_configuration_default" section.
   * @return The value associated with the specified key as a String if found; otherwise, null.
   */
  public static String findAndReturnEarly(HashMap<String, Object> data, String key) {
    if (data == null) {
      return null;
    }
    Object apmConfig = data.get("apm_configuration_default");
    if (apmConfig instanceof HashMap<?, ?>) {
      HashMap<?, ?> tempConfig = (HashMap<?, ?>) apmConfig;
      for (Map.Entry<?, ?> entry : tempConfig.entrySet()) {
        if (entry.getKey() == key) {
          return (String) entry.getValue();
        }
      }
    }
    return null;
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

  private static class StableConfig {
    private final Map<String, Object> apmConfiguration;
    private final String configId;

    StableConfig() {
      this.apmConfiguration = new HashMap<>();
      this.configId = null;
    }

    StableConfig(String configId) {
      this.apmConfiguration = new HashMap<>();
      this.configId = configId;
    }

    StableConfig(String configId, Map<String, Object> config) {
      this.apmConfiguration = config;
      this.configId = configId;
    }

    private String get(String key) {
      return (String) this.apmConfiguration.get(key);
    }

    private Set<String> getKeys() {
      return this.apmConfiguration.keySet();
    }

    private String getConfigId() {
      return this.configId;
    }
  }
}

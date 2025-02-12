package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
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

  private final ConfigOrigin fileOrigin;
  private final String filePath;

  // loadedConfig holds the configuration entries lazily loaded from the configuration file
  private Map<?, ?> loadedConfig;
  // iterator is used to traverse through the entries of the loadedConfig map as processing
  // progresses
  private Iterator<? extends Map.Entry<?, ?>> iterator;
  private Boolean fileReadComplete;

  private final StableConfig config;

  StableConfigSource(String file, ConfigOrigin origin) {
    this.fileOrigin = origin;
    this.filePath = file;
    this.config = new StableConfig();
    // The below are initialized explicitly for clarity
    this.fileReadComplete = false;
    this.iterator = null;
  }

  @Override
  public String get(String key) {
    if (this.fileReadComplete) {
      return this.config.get(key);
    }
    return this.findAndReturnEarly(key);
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

  /**
   * Searches for a specific key and quick returns its associated value if found. This function
   * first checks if the configuration entries have already been loaded from the yaml file. If not,
   * it attempts to load the configuration from the file and process it, returning the key's value
   * if found.
   *
   * @param key the key to search for in the configuration.
   * @return the configuration value corresponding to the given key, or null if not found or if an
   *     error occurs.
   */
  private String findAndReturnEarly(String key) {
    if (this.loadedConfig != null) {
      return iterateAndReturnEarly(key);
    }

    HashMap<String, Object> data = loadConfigDataFromFile();
    if (data == null || data.isEmpty()) {
      this.fileReadComplete = true;
      return null;
    }

    this.config.setConfigId((String) data.get("config_id"));
    Object apmConfig = data.get("apm_configuration_default");

    // If the key apm_configuration_default is not present in the file, or if the value of
    // apm_configuration_default is not convertible to a hashmap, there's nothing to process; return
    // nothing.
    if (!(apmConfig instanceof HashMap<?, ?>)) {
      this.fileReadComplete = true;
      return null;
    }

    this.loadedConfig = (HashMap<?, ?>) apmConfig;
    return iterateAndReturnEarly(key);
  }

  /**
   * Attempts to load configuration data from the file. This function reads the file in YAML format
   * and returns the contents as a HashMap. If an error occurs during the reading or processing of
   * the file, it logs the error and returns null.
   *
   * @return a HashMap containing the configuration data, or null if an error occurs.
   */
  private HashMap<String, Object> loadConfigDataFromFile() {
    try {
      return readYamlFromFile(this.filePath);
    } catch (Exception e) {
      this.fileReadComplete = true;
      log.error("Error processing file: {}", e.getMessage());
      return null;
    }
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
   * Iterates over the configuration entries and attempts to find the value for the given key. The
   * method reuses an existing iterator to avoid re-iterating over the entire set of entries every
   * time it is called, thus optimizing the processing. If the key is found during iteration, the
   * corresponding value is returned immediately.
   *
   * <p>The function also populates the StableConfig `config` map with valid key-value pairs during
   * the iteration. If no value is found for the key, or if the iteration completes without finding
   * the key, the function marks the file as read and returns null.
   *
   * @param key the key to search for in the configuration entries.
   * @return the configuration value associated with the key, or null if not found.
   */
  private String iterateAndReturnEarly(String key) {
    if (this.iterator == null) {
      this.iterator = this.loadedConfig.entrySet().iterator();
    }
    while (this.iterator.hasNext()) {
      Map.Entry<?, ?> entry = iterator.next();
      Object entryKey = entry.getKey();
      Object entryValue = entry.getValue();
      if (entryKey instanceof String && entryValue != null) {
        this.config.put(String.valueOf(entryKey), entryValue);
        String configValue = this.config.get(key);
        if (configValue != null) {
          return configValue;
        }
      }
    }
    this.fileReadComplete = true;
    return null;
  }

  private static class StableConfig {
    private Map<String, Object> apmConfiguration;
    private String configId;

    StableConfig() {
      this.apmConfiguration = new HashMap<>();
      this.configId = null;
    }

    private void put(String key, Object value) {
      this.apmConfiguration.put(key, value);
    }

    private void setConfigId(String configId) {
      this.configId = configId;
    }

    // TODO: Make this.apmConfiguration a Map<String,String> instead
    private String get(String key) {
      return (String) this.apmConfiguration.get(propertyNameToEnvironmentVariableName(key));
    }

    private Set<String> getKeys() {
      return this.apmConfiguration.keySet();
    }

    // Might be null
    private String getConfigId() {
      return this.configId;
    }
  }
}

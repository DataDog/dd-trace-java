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
  public static String USER_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/application_monitoring.yaml"; // MIKAYLA: this should be final, but I need
  // to modify it in my tests because I can't
  // write to /etc/ without sudo persmission.
  public static String MANAGED_STABLE_CONFIG_PATH =
      "/etc/datadog-agent/managed/datadog-apm-libraries/stable/application_monitoring.yaml "; // MIKAYLA: Same for this var.
  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);

  private final ConfigOrigin fileOrigin;
  private final Map<String, Object> configuration;
  private final String configId;

  // MIKAYLA: improvement - if we see that some cached map is already not null by the time the
  // StableConfigurationSource constructor is called, we can skip calling it again.
  StableConfigSource(String file, ConfigOrigin origin) {
    this.fileOrigin = origin;
    HashMap<String, Object> data = readYamlFromFile(file);
    if (data == null) {
      this.configuration = new HashMap<>();
      this.configId = null;
    } else {
      this.configId = (String) data.get("config_id");
      this.configuration = parseStableConfig(data);
    }
  }

  /**
   * Reads configuration data from the YAML file located at the specified file path.
   *
   * <p>If the file is in a valid YAML format, this method returns a {@link HashMap} containing the
   * configuration information.
   *
   * <p>If the file is in an invalid format, the method returns <code>null</code>.
   *
   * @param filePath The path to the YAML file to be read.
   * @return A {@link HashMap} containing the configuration data if the file is valid, or <code>null
   *     </code> if the file is in an invalid format.
   */
  // MIKAYLA: Should improve this so that it caches the resulting map the first time it's called,
  // that way we don't need to call readFromYaml twice;
  // if we see that said cached map is already not null by the name the StableConfigurationSource
  // constructor is called, we can skip calling it again.
  public static Map<String, Object> readYamlFromFile(String filePath) {
    File file = new File(filePath);
    if (!file.exists()) {
      log.debug(
          "Stable configuration file does not exist at the specified path: {}, ignoring", filePath);
      return null;
    }
    System.out.println("file exists");
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    InputStream input;
    try {
      input = Files.newInputStream(new File(filePath).toPath());
    } catch (IOException e) {
      //      throw new RuntimeException(e); // Do we want to do this? Or fail more gracefully?
      log.error("Unable to read from stable config file {}, dropping input", filePath);
      return null;
    }
    try {
      return yaml.load(input);
    } catch (Exception e) {
      log.error("YAML parsing error in stable config file {}: {}", filePath, e.getMessage());
      return null;
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
          log.debug("Config key {} in unexpected format", entry.getKey());
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
    return (String) this.configuration.get(propertyNameToEnvironmentVariableName(key));
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
}

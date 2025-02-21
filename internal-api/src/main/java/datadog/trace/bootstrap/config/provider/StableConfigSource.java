package datadog.trace.bootstrap.config.provider;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;

import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.ConfigOrigin;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final public class StableConfigSource extends ConfigProvider.Source {
  static final String MANAGED_STABLE_CONFIGURATION_PATH = "/etc/datadog-agent/managed/datadog-agent/stable/datadog_apm.yaml";
  static final String LOCAL_STABLE_CONFIGURATION_PATH = "/etc/datadog-agent/datadog_apm.yaml";

  final ConfigOrigin fileOrigin;
  HashMap<String, String> configuration;

  StableConfigSource(String file, ConfigOrigin origin) {
    this.fileOrigin = origin;
    try {
      configuration = parseStableConfig(file);
    } catch (Exception e) {
      configuration = new HashMap();
    }
  }

  private static final HashMap<String, String> parseStableConfig(String filePath) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    InputStream input = new FileInputStream(new File(filePath));
    Object data = yaml.load(input);

    HashMap<Sting, Object> config = new HashMap();
  
    return config; 
  };

  public final String get(String key) {
    return configuration.get(propertyNameToEnvironmentVariableName(key));
  }

  public final ConfigOrigin origin() {
    return fileOrigin;
  }

  private class StableConfig {
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

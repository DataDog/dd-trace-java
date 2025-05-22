package datadog.trace.bootstrap.config.provider.stableconfigyaml;

import java.util.HashMap;
import java.util.Map;

// ConfigurationMap represents configuration key-values found in stable configuration files
public class ConfigurationMap extends HashMap<String, ConfigurationValue> {
  public ConfigurationMap() {
    super();
  }

  public ConfigurationMap(Map<String, ConfigurationValue> map) {
    super(map);
  }
}

class ConfigurationValue {
  private final String value;

  public ConfigurationValue(String value) {
    this.value = value;
  }
}
